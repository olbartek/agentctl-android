package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.AgentContainer
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.AgentStore
import io.github.olbartek.agentctl.CountingClock
import io.github.olbartek.agentctl.IncrementingUuids
import io.github.olbartek.agentctl.MockBackend
import io.github.olbartek.agentctl.MockCallLog
import io.github.olbartek.agentctl.MockFaults
import io.github.olbartek.agentctl.MockLatency
import io.github.olbartek.agentctl.MockMethod
import io.github.olbartek.agentctl.SplitMix64Random
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A real store running in the CLI's process with the deterministic environment CONTRACT.md §6 requires.
 *
 * It builds exactly this [AgentEnvironment] and hands it to the host's `makeStore`:
 *
 * | Field | Headless value |
 * |---|---|
 * | `scope` | a [VirtualTimeDispatcher] scope: every effect runs on one thread, in a fixed order |
 * | `clock` | a [CountingClock] whose `now` is [FIXED_NOW] and whose `sleep` waits on virtual time |
 * | `uuids` | [IncrementingUuids] |
 * | `random` | [SplitMix64Random] seeded with [RANDOM_SEED] |
 * | `zone` | UTC |
 * | `locale` | `en_US_POSIX` |
 * | `mocks` | a fresh call log and fault registry, zero latency, on the clock above |
 *
 * Nothing else is pinned: an app that reads `System.currentTimeMillis()`, `Locale.getDefault()` or
 * `Dispatchers.IO` around the environment is not deterministic headlessly, and `makeStore` is where a host pins
 * whatever else it uses.
 */
public class HeadlessHost<S, A>(
    public val container: AgentContainer<S, A>,
    public val mockMethods: List<MockMethod>,
    makeStore: (AgentEnvironment) -> AgentStore<S, A>,
) {
    public val dispatcher: VirtualTimeDispatcher = VirtualTimeDispatcher()
    public val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** The app's clock: [FIXED_NOW], virtual sleeps, and the source of `pending`. */
    public val clock: CountingClock = CountingClock { FIXED_NOW }

    /** Every mock call, in order: what a step prints as `calls=`. */
    public val callLog: MockCallLog = MockCallLog()

    /** The one-shot failures `mock` arms. */
    public val faults: MockFaults = MockFaults()

    public val environment: AgentEnvironment = AgentEnvironment(
        scope = scope,
        clock = clock,
        mocks = MockBackend(callLog, faults, MockLatency.ZERO, clock, SplitMix64Random(RANDOM_SEED)),
        uuids = IncrementingUuids(),
        random = SplitMix64Random(RANDOM_SEED),
        zone = FIXED_ZONE,
        locale = FIXED_LOCALE,
    )

    public val store: AgentStore<S, A> = makeStore(environment)

    public fun settle(): SettleResult = settleHeadless(
        dispatcher = dispatcher,
        state = { store.state },
        callLog = callLog,
        effectsInFlight = { store.effectsInFlight },
        pending = { clock.activeSleeps },
    )

    public fun makeRunner(): ScriptRunner<S, A> = ScriptRunner(
        store = store,
        container = container,
        callLog = callLog,
        faults = faults,
        pending = { clock.activeSleeps },
        environment = RunnerEnvironment(
            settle = { settle() },
            advance = { duration -> dispatcher.advanceBy(duration.saturatedMilliseconds) },
            synthesizesAppearance = true,
        ),
        mockMethods = mockMethods,
    )

    /** Cancels every effect still running. The host is unusable afterwards. */
    public fun close() {
        scope.cancel()
        dispatcher.runDue()
    }

    public companion object {
        /** 2026-01-01T09:00:00Z: `clock.now()` on every read. */
        public val FIXED_NOW: Instant = Instant.parse("2026-01-01T09:00:00Z")

        /** The seed of `random`: every headless run draws the same numbers in the same order. */
        public const val RANDOM_SEED: Long = 0

        public val FIXED_ZONE: ZoneId = ZoneOffset.UTC

        /** `en_US_POSIX`, the locale that follows no user setting. */
        public val FIXED_LOCALE: Locale = Locale.Builder().setLanguage("en").setRegion("US").setVariant("POSIX").build()
    }
}

/**
 * The app's store as the in-app bridge runs it in debug builds: real time and real mock latency, plus the hooks
 * the agent runtime needs (call log, faults, effect count and a clock that counts pending sleeps).
 *
 * @param dispatcher the store's thread: `Dispatchers.Main.immediate` in an Android app. The bridge runs every
 *   request on it.
 */
public class LiveHost<S, A>(
    public val container: AgentContainer<S, A>,
    public val mockMethods: List<MockMethod>,
    public val latency: MockLatency,
    public val dispatcher: CoroutineDispatcher,
    makeStore: (AgentEnvironment) -> AgentStore<S, A>,
) {
    public val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    public val clock: CountingClock = CountingClock.system()
    public val callLog: MockCallLog = MockCallLog()
    public val faults: MockFaults = MockFaults()

    public val environment: AgentEnvironment = AgentEnvironment(
        scope = scope,
        clock = clock,
        mocks = MockBackend(callLog, faults, latency, clock, Random.Default),
        uuids = { UUID.randomUUID() },
        random = Random.Default,
        zone = ZoneId.systemDefault(),
        locale = Locale.getDefault(),
    )

    public val store: AgentStore<S, A> = makeStore(environment)

    /**
     * Settles on real time — no mock call in flight, and the state quiet for a moment — because a running app's
     * latency and timers are real, unlike the headless host's. Call on [dispatcher].
     */
    public suspend fun settle(): SettleResult = settleLive(
        state = { store.state },
        callLog = callLog,
        pending = { clock.activeSleeps },
        quietWindow = 250.milliseconds,
    )

    /**
     * @param synthesizesAppearance `true` for launch seeding (before any view exists), `false` once the views are
     *   on screen and send their own appearance actions.
     */
    public fun makeRunner(synthesizesAppearance: Boolean): ScriptRunner<S, A> = ScriptRunner(
        store = store,
        container = container,
        callLog = callLog,
        faults = faults,
        pending = { clock.activeSleeps },
        environment = RunnerEnvironment(
            settle = { settle() },
            advance = null,
            synthesizesAppearance = synthesizesAppearance,
        ),
        mockMethods = mockMethods,
    )
}
