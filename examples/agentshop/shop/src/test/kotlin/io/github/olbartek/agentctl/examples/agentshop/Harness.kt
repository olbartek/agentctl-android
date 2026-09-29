package io.github.olbartek.agentctl.examples.agentshop

import io.github.olbartek.agentctl.AgentClock
import io.github.olbartek.agentctl.CountingClock
import io.github.olbartek.agentctl.MockBackend
import io.github.olbartek.agentctl.MockCallLog
import io.github.olbartek.agentctl.MockFaults
import io.github.olbartek.agentctl.MockLatency
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.Store
import io.github.olbartek.agentctl.examples.agentshop.models.ShopCalls
import io.github.olbartek.agentctl.runtime.VirtualTimeDispatcher
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/** 2026-01-01T09:00:00Z, the headless host's date. */
val FIXED_NOW: Instant = Instant.parse("2026-01-01T09:00:00Z")

/**
 * A virtual clock for tests: [clock] sleeps on [dispatcher]'s virtual time, and [uptime] reads it, so [advance] both
 * releases timers and ages the auth server's codes.
 */
class TestTime {
    val dispatcher: VirtualTimeDispatcher = VirtualTimeDispatcher()
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    val clock: CountingClock = CountingClock { FIXED_NOW }
    val uptime: () -> Duration = { dispatcher.currentTime.milliseconds }

    fun advance(duration: Duration) {
        dispatcher.advanceBy(duration.inWholeMilliseconds)
        dispatcher.runDue()
    }
}

/** A mock backend with zero latency, its call log, and the [ShopCalls] shim on it. */
class TestMocks(clock: AgentClock = CountingClock { FIXED_NOW }) {
    val log: MockCallLog = MockCallLog()
    val faults: MockFaults = MockFaults()
    val backend: MockBackend = MockBackend(log, faults, MockLatency.ZERO, clock, Random(0))
    val calls: ShopCalls = ShopCalls(backend)
}

/** Runs a suspending client call to completion: the mocks have zero latency, so nothing waits. */
fun <T> await(block: suspend () -> T): T = runBlocking { block() }

/**
 * The Kotlin stand-in for TCA's `TestStore`: a real [Store] on a virtual-time dispatcher. [send] runs the action and
 * every effect it starts until nothing is due; [received] lists every action the reducer saw, in order, so a test
 * can check the delegates a screen sent.
 */
class TestStore<S, A>(initialState: S, time: TestTime = TestTime(), reducer: Reducer<S, A>) {
    val time: TestTime = time
    val received: MutableList<A> = mutableListOf()
    private val store = Store(initialState, Reducer<S, A> { state, action -> received.add(action); reducer.reduce(state, action) }, time.scope)

    val state: S get() = store.state

    /** Sleeps waiting on the clock: a step's `pending=`. */
    val pending: Int get() = time.clock.activeSleeps

    fun send(action: A): TestStore<S, A> {
        store.send(action)
        time.dispatcher.runDue()
        return this
    }

    fun advance(duration: Duration): TestStore<S, A> {
        time.advance(duration)
        return this
    }
}
