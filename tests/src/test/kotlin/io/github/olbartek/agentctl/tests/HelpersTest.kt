package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.ActiveScreen
import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentCommandError
import io.github.olbartek.agentctl.AgentCommandException
import io.github.olbartek.agentctl.AgentContainer
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.CommandDoc
import io.github.olbartek.agentctl.MockMethod
import io.github.olbartek.agentctl.ResolvedCommand
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.Store
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.accepting
import io.github.olbartek.agentctl.appending
import io.github.olbartek.agentctl.appendingBackFallback
import io.github.olbartek.agentctl.next
import io.github.olbartek.agentctl.runtime.AgentLaunchOptions
import io.github.olbartek.agentctl.runtime.HeadlessHost
import io.github.olbartek.agentctl.Reducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What this guards: the 0.5 helpers (`choice`, the back fallback, inherited container commands, common mock codes,
 * `isRequested`). Their texts are shared with agentctl-ios byte for byte (its `HelpersTests`).
 */
class HelpersTest {
    data class Note(val text: String = "", val saved: Boolean = false)

    sealed interface NoteAction {
        data class TextChanged(val text: String) : NoteAction
        data object SaveTapped : NoteAction
    }

    object NoteAgent : AgentScreen<Note, NoteAction> {
        override val screenPaths = listOf("notes/edit", "notes/saved")
        override fun screenPath(state: Note) = if (state.saved) "notes/saved" else "notes/edit"
        override val summaryKeys = listOf("text")
        override fun summary(state: Note) = listOf(SummaryItem("text", state.text))
        override val commands: List<AgentCommand<Note, NoteAction>> = listOf(
            AgentCommand.text("text", help = "Set the text", paths = listOf("notes/edit")) { NoteAction.TextChanged(it) },
            AgentCommand.action("save", help = "Save", action = NoteAction.SaveTapped, paths = listOf("notes/edit")),
            AgentCommand.choice("pick", listOf("a", "b"), help = "Pick a or b") { NoteAction.TextChanged(it) },
        )
    }

    enum class Mode { CALM, BUSY }

    /**
     * A root container with everything the helpers are for: a stack of notes with its own `back`, a command it offers
     * on every screen beneath it (`mode`, an enum choice), one it offers on one path only (`clear`), and the `back`
     * fallback for when nothing is pushed.
     */
    data class Desk(val root: Note = Note(), val path: List<Pair<Int, Note>> = emptyList(), val mode: Mode = Mode.CALM)

    sealed interface DeskAction {
        data class Root(val action: NoteAction) : DeskAction
        data class PopFrom(val id: Int) : DeskAction
        data class ModeSet(val mode: Mode) : DeskAction
        data object Cleared : DeskAction
    }

    object DeskAgent : AgentContainer<Desk, DeskAction> {
        override val inheritedCommands: List<AgentCommand<Desk, DeskAction>> = listOf(
            AgentCommand.choice("mode", of = Mode.entries, word = { it.name.lowercase() }, help = "Set the mode") { DeskAction.ModeSet(it) },
            AgentCommand.action("clear", help = "Start a new note", action = DeskAction.Cleared, paths = listOf("notes/saved")),
        )

        /** Resolves `back` before the inherited commands on purpose: they must still come first. */
        override fun activeScreen(state: Desk): ActiveScreen<DeskAction> {
            val (id, top) = state.path.lastOrNull()
                ?: return inheritingCommands(NoteAgent.activeScreen(state.root).map { DeskAction.Root(it) }, state)
                    .appendingBackFallback(containerName)
            val back = AgentCommand.action<Desk, DeskAction>("back", help = "Pop", action = DeskAction.PopFrom(id)).resolve(state, containerName)
            val child = NoteAgent.activeScreen(top).map { DeskAction.Root(it) }.identified("#$id")
            return inheritingCommands(child.appending(listOf(back)), state).appendingBackFallback(containerName)
        }

        override val registry: List<ScreenDoc>
            get() {
                val back = CommandDoc("back", null, "Pop", containerName)
                return inheritingCommands(NoteAgent.screenDocs.map { it.inheriting(listOf(back)) })
                    .map { it.inheriting(listOf(CommandDoc.backFallback(containerName))) }
            }

        val reducer = Reducer<Desk, DeskAction> { state, action ->
            when (action) {
                is DeskAction.ModeSet -> next(state.copy(mode = action.mode))
                DeskAction.Cleared -> next(state.copy(root = Note()))
                is DeskAction.Root, is DeskAction.PopFrom -> next(state)
            }
        }

        fun host(mockMethods: List<MockMethod> = emptyList()): HeadlessHost<Desk, DeskAction> =
            HeadlessHost(DeskAgent, mockMethods) { environment -> Store(Desk(), reducer, environment.scope) }
    }

    private fun <A> refused(block: () -> A): AgentCommandError = assertFailsWith<AgentCommandException> { block() }.error

    @Test
    fun choiceDocumentsAndRejectsFromItsOptions() {
        val command = AgentCommand.choice<Note, NoteAction, Mode>("size", of = Mode.entries, word = { it.name.lowercase() }, help = "Pick a size") {
            NoteAction.TextChanged(it.name)
        }.resolve(Note(), "Note")
        assertEquals("size <calm|busy>", command.usage)
        assertEquals(NoteAction.TextChanged("BUSY"), command.makeAction("busy"))
        assertEquals(AgentCommandError.InvalidArgument("expected calm|busy"), refused { command.makeAction("medium") })
        assertEquals(AgentCommandError.MissingArgument("<calm|busy>"), refused { command.makeAction(null) })
        assertEquals("invalid argument: expected calm|busy", AgentCommandError.InvalidArgument("expected calm|busy").message)
    }

    @Test
    fun choiceWithPairsAndPlainWords() {
        val filter = AgentCommand.choice<Note, NoteAction, Mode?>("filter", listOf("all" to null, "calm" to Mode.CALM), help = "Filter") {
            NoteAction.TextChanged(it?.name ?: "-")
        }.doc("Note")
        assertEquals("filter <all|calm>", filter.usage)
        val words = AgentCommand.choice<Note, NoteAction>("pick", listOf("a", "b"), help = "Pick a or b") { NoteAction.TextChanged(it) }
            .resolve(Note(), "Note")
        assertEquals("<a|b>", words.argument)
        assertEquals(NoteAction.TextChanged("b"), words.makeAction("b"))
        // Exact words only: no trimming, no case folding.
        assertEquals(AgentCommandError.InvalidArgument("expected a|b"), refused { words.makeAction("A") })
    }

    @Test
    fun choiceRefusesAMalformedOptionList() {
        fun choice(vararg words: String) = AgentCommand.choice<Note, NoteAction>("pick", words.toList(), help = "Pick") { NoteAction.TextChanged(it) }
        assertEquals("choice 'pick' needs at least one option", assertFailsWith<IllegalArgumentException> { choice() }.message)
        assertEquals("choice 'pick' lists an option twice: [a, a]", assertFailsWith<IllegalArgumentException> { choice("a", "a") }.message)
        for (bad in listOf("", "a|b", "a b")) {
            assertEquals(
                "choice 'pick' has an option that is empty or contains '|' or whitespace: [$bad]",
                assertFailsWith<IllegalArgumentException> { choice(bad) }.message,
            )
        }
    }

    @Test
    fun onOffIsAChoice() {
        val pin = AgentCommand.onOff<Note, NoteAction>("pin", help = "Pin") { NoteAction.TextChanged("$it") }.resolve(Note(), "Note")
        assertEquals("pin <on|off>", pin.usage)
        assertEquals(NoteAction.TextChanged("false"), pin.makeAction("off"))
        assertEquals(AgentCommandError.InvalidArgument("expected on|off"), refused { pin.makeAction("maybe") })
    }

    @Test
    fun inheritedCommandsGoBeforeTheContainersOwnWhateverTheOrder() {
        val state = Desk()
        assertEquals(listOf("text", "save", "pick", "mode", "back"), DeskAgent.activeScreen(state).commands.map { it.name })
        assertEquals(listOf("Note", "Note", "Note", "Desk", "Desk"), DeskAgent.activeScreen(state).commands.map { it.source })
        assertEquals(DeskAction.ModeSet(Mode.BUSY), DeskAgent.activeScreen(state).command("mode")?.makeAction("busy"))

        val pushed = state.copy(path = listOf(7 to Note(text = "x", saved = true)))
        val screen = DeskAgent.activeScreen(pushed)
        assertEquals("notes/saved", screen.path)
        assertEquals(listOf("pick", "mode", "clear", "back"), screen.commands.map { it.name })
        assertEquals(DeskAction.PopFrom(7), screen.command("back")?.makeAction(null))
    }

    @Test
    fun theRegistryListsInheritedCommandsWhereTheActiveScreenHasThem() {
        val docs = DeskAgent.registry
        assertEquals(listOf("notes/edit", "notes/saved"), docs.map { it.path })
        assertEquals(listOf("text <text>", "save", "pick <a|b>", "mode <calm|busy>", "back"), docs[0].commands.map { it.usage })
        assertEquals(listOf("pick <a|b>", "mode <calm|busy>", "clear", "back"), docs[1].commands.map { it.usage })
        assertEquals(listOf("Note", "Desk", "Desk", "Desk"), docs[1].commands.map { it.source })
        assertEquals("Pop", docs[1].commands.last().help)
    }

    @Test
    fun aDescendantShadowsAnInheritedCommand() {
        val shadowing = object : AgentContainer<Note, NoteAction> {
            override val containerName = "Shadowing"
            override val inheritedCommands = listOf(AgentCommand.action<Note, NoteAction>("pick", help = "Shadowed", action = NoteAction.SaveTapped))
            override fun activeScreen(state: Note) = inheritingCommands(NoteAgent.activeScreen(state), state)
            override val registry: List<ScreenDoc> get() = inheritingCommands(NoteAgent.screenDocs)
        }
        assertEquals("Note", shadowing.activeScreen(Note()).command("pick")?.source)
        assertEquals(listOf("Pick a or b"), shadowing.registry[0].commands.filter { it.name == "pick" }.map { it.help })
    }

    @Test
    fun theDefaultContainerNameDropsAgent() {
        assertEquals("Desk", DeskAgent.containerName)
    }

    @Test
    fun theBackFallbackFailsNamingThePath() {
        val back = ResolvedCommand.backFallback<NoteAction>("home/shop", "Root")
        assertTrue(back.name == "back" && back.argument == null)
        assertNull(back.disabledReason)
        assertEquals(AgentCommandError.NotApplicable("nothing to go back to on home/shop"), refused { back.makeAction(null) })
        val help = "Fails with 'nothing to go back to' when no screen is pushed."
        assertEquals(CommandDoc("back", null, help, "Root"), CommandDoc.backFallback("Root"))
        assertEquals(help, back.help)
    }

    @Test
    fun commonMockCodesFollowEachMethodsOwn() {
        val methods = listOf(
            MockMethod("auth.signIn", listOf("invalidCredentials", "locked")),
            MockMethod("auth.refresh", listOf("network", "expired")),
            MockMethod("auth.signOut", emptyList()),
        ).accepting(listOf("network", "timeout"))
        assertEquals(
            listOf(
                listOf("invalidCredentials", "locked", "network", "timeout"),
                listOf("network", "expired", "timeout"),
                listOf("network", "timeout"),
            ),
            methods.map { it.errorCodes },
        )
        assertEquals(MockMethod("a.b", listOf("x")), MockMethod("a.b", listOf("x")).accepting(listOf("x")))
    }

    @Test
    fun theBridgeIsRequestedByAnAgentPort() {
        assertTrue(AgentLaunchOptions.isRequested(listOf("/path/App", "-agent-port", "8799")))
        assertTrue(AgentLaunchOptions.isRequested(listOf("/path/App", "-agent-port")))
        assertFalse(AgentLaunchOptions.isRequested(listOf("/path/App")))
        // Seeding or latency alone is not an opt-in: only `app launch` passes `-agent-port`, and it always does.
        assertFalse(AgentLaunchOptions.isRequested(listOf("/path/App", "-appctl-seed", "open 1", "-mock-latency", "0")))
        assertFalse(AgentLaunchOptions.isRequested(listOf("/path/App", "-agent-port=8799")))
    }

    /** The helpers' texts as a script sees them: the failed step's message, which is what an agent reads. */
    private fun failure(script: String, mockMethods: List<MockMethod> = emptyList()): String? = runBlocking {
        val runner = DeskAgent.host(mockMethods).makeRunner()
        runner.launch()
        runner.run(script).steps.lastOrNull()?.message
    }

    @Test
    fun aWrongChoiceNamesTheOptions() {
        assertEquals("mode <calm|busy>: invalid argument: expected calm|busy", failure("mode frantic"))
        assertEquals("mode <calm|busy>: missing argument: expected <calm|busy>", failure("mode"))
    }

    @Test
    fun backWithNothingPushedSaysSo() {
        assertEquals("back: nothing to go back to on notes/edit", failure("back"))
    }

    @Test
    fun aCommonCodeIsAcceptedAndListedAfterTheMethodsOwn() {
        val methods = listOf(MockMethod("notes.save", listOf("full"))).accepting(listOf("network"))
        assertEquals("unknown error 'x' for notes.save; valid: full, network", failure("mock notes.save network; mock notes.save x", methods))
    }
}
