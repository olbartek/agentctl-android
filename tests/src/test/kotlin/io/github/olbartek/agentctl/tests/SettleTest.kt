package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.ActiveScreen
import io.github.olbartek.agentctl.AgentContainer
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.Store
import io.github.olbartek.agentctl.StepFormatter
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.every
import io.github.olbartek.agentctl.next
import io.github.olbartek.agentctl.runtime.AgentLaunchSession
import io.github.olbartek.agentctl.runtime.HeadlessHost
import io.github.olbartek.agentctl.runtime.RunStatus
import io.github.olbartek.agentctl.runtime.SettleResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * What this guards: headless settling on toy reducers, independently of any app's screens. `Toy` covers the effect
 * shapes a real reducer produces: immediate, chained, clock-suspended, a repeating timer, a dispatcher hop, and
 * cancellation.
 */
object Toy {
    data class State(val value: Int = 0, val ticks: Int = 0)

    sealed interface Action {
        data object Immediate : Action
        data class Chain(val remaining: Int) : Action
        data object Sleep : Action
        data object StartTimer : Action
        data object StopTimer : Action
        data object Tick : Action
        data class SetValue(val value: Int) : Action
        data object Backend : Action
        data object Restless : Action
    }

    private object TimerId

    fun reducer(environment: AgentEnvironment) = Reducer<State, Action> { state, action ->
        when (action) {
            Action.Immediate -> next(state, Effect.run { send -> send(Action.SetValue(1)) })
            is Action.Chain -> next(
                state.copy(value = state.value + 1),
                if (action.remaining > 1) Effect.run { send -> send(Action.Chain(action.remaining - 1)) } else Effect.None,
            )
            Action.Sleep -> next(state, Effect.run { send ->
                environment.clock.sleep(30.seconds)
                send(Action.SetValue(30))
            })
            Action.StartTimer -> next(
                state,
                Effect.run<Action> { send -> environment.clock.every(1.seconds) { send(Action.Tick) } }.cancellable(TimerId, cancelInFlight = true),
            )
            Action.StopTimer -> next(state, Effect.cancel(TimerId))
            Action.Tick -> {
                val ticks = state.ticks + 1
                next(state.copy(ticks = ticks), if (ticks == 3) Effect.cancel(TimerId) else Effect.None)
            }
            is Action.SetValue -> next(state.copy(value = action.value))
            Action.Backend -> next(state, Effect.run { send ->
                val value = environment.mocks.call("toy.answer", { Exception(it) }) { 42 }
                send(Action.SetValue(value))
            })
            // What a real-time dependency leaking into a headless run looks like: the state never stops changing.
            Action.Restless -> next(state, Effect.run { send ->
                while (true) {
                    send(Action.SetValue(state.value + 1))
                    yield()
                }
            })
        }
    }

    /** One screen, `toy`, whose appearance can be made restless. */
    class Container(private val appear: Action?) : AgentContainer<State, Action> {
        override fun activeScreen(state: State) = ActiveScreen(
            path = "toy",
            identity = "toy",
            summary = listOf(SummaryItem("value", state.value), SummaryItem("ticks", state.ticks)),
            errorCode = null,
            appearAction = appear,
            commands = emptyList(),
        )

        override val registry: List<ScreenDoc> = listOf(ScreenDoc("toy", "Toy", emptyList(), listOf("value", "ticks")))
    }

    fun host(appear: Action? = null) = HeadlessHost(Container(appear), emptyList()) { environment ->
        Store(State(), reducer(environment), environment.scope)
    }
}

class SettleTest {
    private val host = Toy.host()

    private fun send(action: Toy.Action): SettleResult {
        host.store.send(action)
        return host.settle()
    }

    private val state get() = host.store.state

    @Test
    fun immediateEffectIsAppliedWithinOneStep() {
        assertEquals(SettleResult(true, 0), send(Toy.Action.Immediate))
        assertEquals(1, state.value)
    }

    @Test
    fun chainedEffectsAllApply() {
        assertEquals(SettleResult(true, 0), send(Toy.Action.Chain(3)))
        assertEquals(3, state.value)
    }

    @Test
    fun mockCallsInsideEffectsSettle() {
        assertEquals(0, send(Toy.Action.Backend).pending)
        assertEquals(42, state.value)
        assertEquals(listOf("toy.answer"), host.callLog.all)
    }

    @Test
    fun clockSuspendedEffectIsPendingUntilAdvanced() {
        assertEquals(SettleResult(true, 1), send(Toy.Action.Sleep))
        assertEquals(0, state.value)
        host.dispatcher.advanceBy(29_000)
        assertEquals(1, host.settle().pending)
        host.dispatcher.advanceBy(1_000)
        assertEquals(SettleResult(true, 0), host.settle())
        assertEquals(30, state.value)
    }

    @Test
    fun timerTicksAndCancelsItself() {
        assertEquals(1, send(Toy.Action.StartTimer).pending)
        host.dispatcher.advanceBy(2_000)
        assertEquals(1, host.settle().pending)
        assertEquals(2, state.ticks)
        host.dispatcher.advanceBy(5_000)
        assertEquals(0, host.settle().pending)
        assertEquals(3, state.ticks)
        assertEquals(0, host.store.effectsInFlight)
    }

    @Test
    fun cancellationReleasesThePendingEffect() {
        assertEquals(1, send(Toy.Action.StartTimer).pending)
        assertEquals(0, send(Toy.Action.StopTimer).pending)
        host.dispatcher.advanceBy(10_000)
        assertEquals(0, state.ticks)
        assertEquals(0, host.store.effectsInFlight)
    }

    /** An effect cancelled before it was ever dispatched still stops counting as in flight. */
    @Test
    fun anEffectCancelledBeforeItRanIsNotLeftInFlight() {
        host.store.send(Toy.Action.StartTimer)
        host.store.send(Toy.Action.StopTimer)
        assertEquals(SettleResult(true, 0), host.settle())
        assertEquals(0, host.store.effectsInFlight)
    }

    @Test
    fun settlingIsFast() {
        val start = TimeSource.Monotonic.markNow()
        repeat(10) { send(Toy.Action.Chain(2)) }
        assertTrue(start.elapsedNow() < 0.5.seconds)
    }

    /** A leaked dispatcher is waited for while it keeps the effect in flight, and its result lands. */
    @Test
    fun aHopToAnotherDispatcherIsWaitedFor() {
        host.store.send(Toy.Action.Immediate)
        val store = host.store
        val environment = host.environment
        environment.scope.launch {
            val value = withContext(Dispatchers.Default) { 7 }
            store.send(Toy.Action.SetValue(value))
        }
        host.settle()
        // The hop resumes on the host's dispatcher, so the value lands once it is run again.
        val deadline = TimeSource.Monotonic.markNow()
        while (state.value != 7 && deadline.elapsedNow() < 2.seconds) host.settle()
        assertEquals(7, state.value)
    }

    /**
     * CONTRACT.md §3.1 and §5: a step that does not settle is printed with `settled=false`, fails with exit 1, and a
     * `(launch)` that does not settle runs no line of the script.
     */
    @Test
    fun aLaunchThatDoesNotSettleFails() = runBlocking {
        val runner = Toy.host(appear = Toy.Action.Restless).makeRunner()
        val launch = runner.launch()
        assertEquals(RunStatus.FAILED, launch.status)
        assertFalse(launch.step.settled)
        val text = StepFormatter.text(launch.step)
        assertTrue(text.startsWith("> (launch)\n  screen=toy "), text)
        assertTrue(text.contains(" settled=false\n"), text)
        assertTrue(text.endsWith("  FAIL did not settle within the time limit (a real-time dependency may have leaked in)"), text)
    }

    /** A launch seed is a script: a launch that never settles fails it before its first command. */
    @Test
    fun aSeedStopsWhenTheLaunchDoesNotSettle() = runBlocking {
        val seed = AgentLaunchSession.applySeed("expect screen=toy", Toy.host(appear = Toy.Action.Restless).makeRunner())
        assertEquals(RunStatus.FAILED, seed.status)
        assertTrue(seed.log.startsWith("AgentCtlBridge: seed FAILED (exit 1)\n> (launch)"), seed.log)
        assertTrue(seed.log.contains("settled=false"))
        assertFalse(seed.log.contains("> expect"), "the seed ran on a launch that did not settle:\n${seed.log}")
    }
}

