package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentCommandError
import io.github.olbartek.agentctl.AgentCommandException
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.CommandDoc
import io.github.olbartek.agentctl.CommandGate
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.appending
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** What this guards: screens, commands and containers as a host declares them, independently of TinyApp. */
class VocabularyTest {
    data class Note(val text: String = "", val locked: Boolean = false, val mode: String = "edit")

    sealed interface NoteAction {
        data class SetText(val text: String) : NoteAction
        data class Pin(val on: Boolean) : NoteAction
        data object Save : NoteAction
    }

    object NoteAgent : AgentScreen<Note, NoteAction> {
        override val screenPaths = listOf("notes/edit", "notes/view")
        override fun screenPath(state: Note) = "notes/${state.mode}"
        override val summaryKeys = listOf("text")
        override fun summary(state: Note) = listOf(SummaryItem("text", state.text))
        override val commands: List<AgentCommand<Note, NoteAction>> = listOf(
            AgentCommand.text("text", help = "Set the text.", paths = listOf("notes/edit")) { NoteAction.SetText(it) },
            AgentCommand.onOff("pin", help = "Pin the note.") { NoteAction.Pin(it) },
            AgentCommand.action("save", help = "Save.", action = NoteAction.Save, gate = CommandGate("locked=true") { !it.locked }),
            AgentCommand.action("back", help = "The note's own back.", action = NoteAction.Save),
        )
    }

    @Test
    fun theDefaultScreenNameDropsAgent() {
        assertEquals("Note", NoteAgent.screenName)
    }

    @Test
    fun commandsAreFilteredByPath() {
        assertEquals(listOf("text", "pin", "save", "back"), NoteAgent.activeScreen(Note()).commands.map { it.name })
        assertEquals(listOf("pin", "save", "back"), NoteAgent.activeScreen(Note(mode = "view")).commands.map { it.name })
        assertEquals(listOf("pin", "save", "back"), NoteAgent.screenDocs[1].commands.map { it.name })
    }

    @Test
    fun gatesAndArguments() {
        val screen = NoteAgent.activeScreen(Note(locked = true))
        assertEquals("locked=true", screen.command("save")?.disabledReason)
        assertNull(NoteAgent.activeScreen(Note()).command("save")?.disabledReason)
        assertEquals(NoteAction.SetText("Alice Smith"), screen.command("text")!!.makeAction("Alice Smith"))
        assertEquals(
            AgentCommandError.MissingArgument("<text>"),
            assertFailsWith<AgentCommandException> { screen.command("text")!!.makeAction(null) }.error,
        )
        assertEquals(
            "invalid argument: expected on|off",
            assertFailsWith<AgentCommandException> { screen.command("pin")!!.makeAction("maybe") }.error.message,
        )
        assertEquals(NoteAction.Pin(true), screen.command("pin")!!.makeAction("on"))
        assertEquals("pin <on|off>", screen.command("pin")!!.usage)
        assertEquals(CommandDoc("save", null, "Save.", "Note", "disabled when locked=true"), NoteAgent.screenDocs[0].commands[2])
    }

    /** Leaf first: a screen's own command shadows its container's command of the same name (CONTRACT.md §1.3). */
    @Test
    fun aScreenShadowsItsContainersCommand() {
        val containerBack = AgentCommand.action<Note, NoteAction>("back", help = "Pop.", action = NoteAction.Save).resolve(Note(), "Stack")
        val containerHome = AgentCommand.action<Note, NoteAction>("home", help = "Home.", action = NoteAction.Save).resolve(Note(), "Stack")
        val screen = NoteAgent.activeScreen(Note()).appending(listOf(containerBack, containerHome))
        assertEquals(listOf("text", "pin", "save", "back", "home"), screen.commands.map { it.name })
        assertEquals("Note", screen.command("back")?.source)
        assertEquals("#3/notes/edit", screen.identified("#3").identity)
        val doc = ScreenDoc("notes/edit", "Note", listOf(CommandDoc("back", null, "Own.", "Note")), emptyList())
            .inheriting(listOf(CommandDoc("back", null, "Pop.", "Stack"), CommandDoc("home", null, "Home.", "Stack")))
        assertEquals(listOf("Note", "Stack"), doc.commands.map { it.source })
    }
}
