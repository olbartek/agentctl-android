package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.ActiveScreen
import io.github.olbartek.agentctl.AgentContainer
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.ResolvedCommand
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.StepFormatter
import io.github.olbartek.agentctl.Store
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.next
import io.github.olbartek.agentctl.runtime.HeadlessHost
import io.github.olbartek.agentctl.runtime.RunStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/**
 * What this guards: headless "now" (CONTRACT.md §6) starts at 2026-01-01T09:00:00Z and moves only with `advance`,
 * so what the app reads as the date after `advance 90s` is 90 seconds later, and a timer that wakes sees the time it
 * woke at. A Kotlin screen's summary and commands see only the state, so a date rule lives in the reducer, which
 * reads `environment.clock`.
 */
class HeadlessNowTest {
    private object Dated {
        data class State(val readAt: Instant? = null, val wokeAt: Instant? = null)

        sealed interface Action {
            data object Read : Action
            data object Nap : Action
            data class Woke(val at: Instant) : Action
        }

        fun reducer(environment: AgentEnvironment) = Reducer<State, Action> { state, action ->
            when (action) {
                Action.Read -> next(state.copy(readAt = environment.clock.now()))
                Action.Nap -> next(state, Effect.run { send ->
                    environment.clock.sleep(30.seconds)
                    send(Action.Woke(environment.clock.now()))
                })
                is Action.Woke -> next(state.copy(wokeAt = action.at))
            }
        }

        object Container : AgentContainer<State, Action> {
            override fun activeScreen(state: State) = ActiveScreen(
                path = "dated",
                identity = "dated",
                summary = listOf(SummaryItem("readAt", state.readAt?.toString() ?: "none"), SummaryItem("wokeAt", state.wokeAt?.toString() ?: "none")),
                errorCode = null,
                appearAction = null,
                commands = listOf(
                    ResolvedCommand("read", null, "Read the date.", "Dated", null) { Action.Read },
                    ResolvedCommand("nap", null, "Sleep 30 s, then read the date.", "Dated", null) { Action.Nap },
                ),
            )

            override val registry: List<ScreenDoc> =
                listOf(ScreenDoc("dated", "Dated", emptyList(), listOf("readAt", "wokeAt")))
        }

        fun host() = HeadlessHost(Container, emptyList()) { environment -> Store(State(), reducer(environment), environment.scope) }
    }

    private fun run(script: String): List<String> = runBlocking {
        val runner = Dated.host().makeRunner()
        runner.launch()
        val result = runner.run(script)
        assertEquals(RunStatus.OK, result.status, result.steps.joinToString("\n") { StepFormatter.text(it) })
        result.steps.map { step -> step.summary.first { it.key == "readAt" }.value }
    }

    @Test
    fun theDateStartsFixedAndMovesOnlyWithAdvance() {
        assertEquals(
            listOf("2026-01-01T09:00:00Z", "2026-01-01T09:00:00Z", "2026-01-01T09:01:30Z"),
            run("read; advance 90s; read"),
        )
    }

    @Test
    fun theDateDoesNotMoveWithoutAdvance() {
        assertEquals(listOf("2026-01-01T09:00:00Z", "2026-01-01T09:00:00Z"), run("read; read"))
    }

    @Test
    fun aTimerWakesAtTheTimeItWasDueAt() = runBlocking {
        val host = Dated.host()
        val runner = host.makeRunner()
        runner.launch()
        val result = runner.run("nap; advance 45s")
        assertEquals(RunStatus.OK, result.status)
        assertEquals(Instant.parse("2026-01-01T09:00:30Z"), host.store.state.wokeAt)
        assertEquals(Instant.parse("2026-01-01T09:00:45Z"), host.environment.clock.now())
    }
}
