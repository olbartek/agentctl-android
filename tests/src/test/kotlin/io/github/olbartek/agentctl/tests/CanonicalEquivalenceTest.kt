package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentContainer
import io.github.olbartek.agentctl.AgentDevice
import io.github.olbartek.agentctl.AgentDeviceConfig
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.MockMethod
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.StepFormatter
import io.github.olbartek.agentctl.Store
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.next
import io.github.olbartek.agentctl.runtime.HeadlessHost
import io.github.olbartek.agentctl.runtime.RunStatus
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** A one-screen app whose every name a script can type has an accent, declared precomposed (NFC). */
object Cafe {
    data class State(val ordered: String = "no", val size: String = "none", val fetched: String = "none", val pings: Int = 0)

    sealed interface Action {
        data object Order : Action
        data class Size(val word: String) : Action
        data object Fetch : Action
        data class Fetched(val result: String) : Action
        data object Pinged : Action
    }

    class CafeError(val code: String) : Exception(code)

    fun reducer(environment: AgentEnvironment) = Reducer<State, Action> { state, action ->
        when (action) {
            Action.Order -> next(state.copy(ordered = "oui"))
            is Action.Size -> next(state.copy(size = action.word))
            Action.Fetch -> next(
                state,
                Effect.run { send ->
                    try {
                        environment.mocks.call("café.fetch", ::CafeError) {}
                        send(Action.Fetched("ok"))
                    } catch (error: CafeError) {
                        send(Action.Fetched(error.code))
                    }
                },
            )
            is Action.Fetched -> next(state.copy(fetched = action.result))
            Action.Pinged -> next(state.copy(pings = state.pings + 1))
        }
    }

    object Screen : AgentScreen<State, Action>, AgentContainer<State, Action> {
        override val screenPaths: List<String> = listOf("café")

        override fun screenPath(state: State): String = "café"

        override val summaryKeys: List<String> = listOf("commandé", "taille", "reçu", "pings")

        override fun summary(state: State): List<SummaryItem> = listOf(
            SummaryItem("commandé", state.ordered), SummaryItem("taille", state.size), SummaryItem("reçu", state.fetched),
            SummaryItem("pings", state.pings.toString()),
        )

        override val commands: List<AgentCommand<State, Action>> = listOf(
            AgentCommand.action("café", "Order a coffee.", Action.Order),
            AgentCommand.choice("taille", listOf("crème", "noir"), "Pick a size.") { Action.Size(it) },
            AgentCommand.action("fetch", "Fetch.", Action.Fetch),
        )

        override val registry: List<ScreenDoc> get() = screenDocs
    }

    fun host() = HeadlessHost(
        Screen,
        listOf(MockMethod("café.fetch", listOf("réseau"))),
        AgentDeviceConfig<Action>(permissions = listOf("caméra"), pushes = listOf(AgentDevice.Push<Action>("thé", help = "Tea.", received = Action.Pinged, opened = Action.Pinged))),
    ) { environment -> Store(State(), reducer(environment), environment.scope) }
}

/**
 * What this guards: a script's text is compared by canonical equivalence, as Swift's `String ==` compares it, in every
 * lookup it goes through and in `expect`. `é` typed as `e` + U+0301 names the same command, key, value, mock method,
 * error, choice word, permission and push as a precomposed `é`. What a step prints is unchanged: the command as typed,
 * the values as the app gives them.
 */
class CanonicalEquivalenceTest {
    private fun decomposed(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)

    @Test
    fun aDecomposedScriptReachesThePrecomposedApp() = runBlocking {
        val script = decomposed(
            "café; taille crème; mock café.fetch réseau; fetch; expect call=café.fetch reçu=réseau; " +
                "device permission caméra granted; device push thé; expect screen=café commandé=oui taille=crème pings=1",
        )
        assertTrue(script != Normalizer.normalize(script, Normalizer.Form.NFC), "the script is really decomposed")
        val runner = Cafe.host().makeRunner()
        runner.launch()
        val result = runner.run(script)
        val text = StepFormatter.text(result.steps)
        assertEquals(RunStatus.OK, result.status, text)
        // Each command is printed as typed; the summary as the app gives it, precomposed.
        assertEquals(script.split("; ").map { "> $it" }, text.lines().filter { it.startsWith("> ") })
        assertTrue("  screen=café commandé=oui taille=crème reçu=réseau pings=1\n" in text, text)
    }

    @Test
    fun aMismatchIsStillReported() = runBlocking {
        val runner = Cafe.host().makeRunner()
        runner.launch()
        val result = runner.run(decomposed("expect taille=crème"))
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("expected taille=${decomposed("crème")}, got taille=none", result.steps.last().message)
    }
}
