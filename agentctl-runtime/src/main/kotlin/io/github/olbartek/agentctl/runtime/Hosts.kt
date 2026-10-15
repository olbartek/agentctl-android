package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.AgentContainer
import io.github.olbartek.agentctl.AgentDevice
import io.github.olbartek.agentctl.AgentDeviceConfig
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job

/**
 * A real store running in the CLI's process with the deterministic environment CONTRACT.md §6 requires.
 *
 * It builds exactly this [AgentEnvironment] and hands it to the host's `makeStore`:
 *
 * | Field | Headless value |
 * |---|---|
 * | `scope` | a [VirtualTimeDispatcher] scope: every effect runs on one thread, in a fixed order |
 * | `clock` | a [CountingClock] whose `now` is [FIXED_NOW] plus what `advance` has added, and whose `sleep` waits on
 *   virtual time |
 * | `uuids` | [IncrementingUuids] |
 * | `random` | [SplitMix64Random] seeded with [RANDOM_SEED] |
 * | `zone` | UTC |
 * | `locale` | `en_US_POSIX` |
 * | `mocks` | a fresh call log and fault registry, zero latency, on the clock above |
 * | `device` | a driven [AgentDevice] |
 *
 * Nothing else is pinned: an app that reads `System.currentTimeMillis()`, `Locale.getDefault()` or
 * `Dispatchers.IO` around the environment is not deterministic headlessly, and `makeStore` is where a host pins
 * whatever else it uses.
 */
public class HeadlessHost<S, A>(
    public val container: AgentContainer<S, A>,
    public val mockMethods: List<MockMethod>,
    /** What of the device scripts can drive, and the actions its events become (`device`). */
    public val deviceConfig: AgentDeviceConfig<A> = AgentDeviceConfig.none(),
    private val makeStore: (AgentEnvironment) -> AgentStore<S, A>,
) {
    /** A host without a device configuration: no `device` command. */
    public constructor(
        container: AgentContainer<S, A>,
        mockMethods: List<MockMethod>,
        makeStore: (AgentEnvironment) -> AgentStore<S, A>,
    ) : this(container, mockMethods, AgentDeviceConfig.none(), makeStore)

    public val dispatcher: VirtualTimeDispatcher = VirtualTimeDispatcher()

    /** The host's scope. Each store runs its effects in a child of it, which `device relaunch` cancels. */
    public val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * The app's clock: [FIXED_NOW] plus the virtual time `advance` has added (CONTRACT.md §6), virtual sleeps, and
     * the source of `pending`. A timer that wakes reads the time it was due at.
     */
    public val clock: CountingClock = CountingClock { FIXED_NOW.plusMillis(dispatcher.currentTime) }

    /** Every mock call, in order: what a step prints as `calls=`. */
    public val callLog: MockCallLog = MockCallLog()

    /** The one-shot failures `mock` arms. */
    public val faults: MockFaults = MockFaults()

    /** The simulated device, which `device` changes. */
    public val device: AgentDevice = AgentDevice(isDriven = true, offlineErrorCode = deviceConfig.offlineErrorCode)

    private val mocks = MockBackend(callLog, faults, MockLatency.ZERO, clock, SplitMix64Random(RANDOM_SEED), device)
    private val uuids = IncrementingUuids()
    private val random = SplitMix64Random(RANDOM_SEED)

    /**
     * What the current store was built on. `device relaunch` builds the next store on a copy whose only difference is
     * a fresh [AgentEnvironment.scope], so the old store's effects end with it while everything else carries over.
     */
    public var environment: AgentEnvironment = makeEnvironment()
        private set

    /** The app's store. `device relaunch` replaces it with a fresh one. */
    public var store: AgentStore<S, A> = makeStore(environment)
        private set

    private fun makeEnvironment(): AgentEnvironment = AgentEnvironment(
        scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job)),
        clock = clock,
        mocks = mocks,
        uuids = uuids,
        random = random,
        zone = FIXED_ZONE,
        locale = FIXED_LOCALE,
        device = device,
    )

    /** Cancels the store's effects and builds a fresh store from its initial state: `device relaunch`. */
    private fun relaunch(): AgentStore<S, A> {
        environment.scope.cancel()
        environment = makeEnvironment()
        store = makeStore(environment)
        return store
    }

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
        device = device,
        deviceConfig = deviceConfig,
        relaunch = ::relaunch,
    )

    /** Cancels every effect still running. The host is unusable afterwards. */
    public fun close() {
        scope.cancel()
        dispatcher.runDue()
    }

    public companion object {
        /** 2026-01-01T09:00:00Z: `clock.now()` before any `advance`. */
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
 * Time is real, but `advance` can move it forward: the environment's clock is an [AdvanceableClock], whose `now()`
 * moves with it. A backend of the host's own that reads the time or sleeps should do so on `environment.clock`, so
 * `advance` moves it too.
 *
 * @param dispatcher the store's thread: `Dispatchers.Main.immediate` in an Android app. The bridge runs every
 *   request on it.
 */
public class LiveHost<S, A>(
    public val container: AgentContainer<S, A>,
    public val mockMethods: List<MockMethod>,
    public val latency: MockLatency,
    public val dispatcher: CoroutineDispatcher,
    /** What of the device scripts can drive, and the actions its events become (`device`). */
    public val deviceConfig: AgentDeviceConfig<A> = AgentDeviceConfig.none(),
    private val makeStore: (AgentEnvironment) -> AgentStore<S, A>,
) {
    /** A host without a device configuration: no `device` command. */
    public constructor(
        container: AgentContainer<S, A>,
        mockMethods: List<MockMethod>,
        latency: MockLatency,
        dispatcher: CoroutineDispatcher,
        makeStore: (AgentEnvironment) -> AgentStore<S, A>,
    ) : this(container, mockMethods, latency, dispatcher, AgentDeviceConfig.none(), makeStore)

    /** The host's scope. Each store runs its effects in a child of it, which `device relaunch` cancels. */
    public val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    /** The app's clock: real time that `advance` moves forward, with its sleeps counted for `pending`. */
    public val clock: AdvanceableClock = AdvanceableClock()
    public val callLog: MockCallLog = MockCallLog()
    public val faults: MockFaults = MockFaults()

    /** The simulated device, which `device` changes. */
    public val device: AgentDevice = AgentDevice(isDriven = true, offlineErrorCode = deviceConfig.offlineErrorCode)

    private val mocks = MockBackend(callLog, faults, latency, clock, Random.Default, device)

    /** What the current store was built on; `device relaunch` gives the next store a fresh [AgentEnvironment.scope]. */
    public var environment: AgentEnvironment = makeEnvironment()
        private set

    /** The app's store. `device relaunch` replaces it; [onRelaunch] hears of it, so the app shows the new one. */
    public var store: AgentStore<S, A> = makeStore(environment)
        private set

    /** Called on [dispatcher] with the new store after `device relaunch`: `agentctl-bridge`'s `AgentLaunch` shows it. */
    public var onRelaunch: ((AgentStore<S, A>) -> Unit)? = null

    private fun makeEnvironment(): AgentEnvironment = AgentEnvironment(
        scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job)),
        clock = clock,
        mocks = mocks,
        uuids = { UUID.randomUUID() },
        random = Random.Default,
        zone = ZoneId.systemDefault(),
        locale = Locale.getDefault(),
        device = device,
    )

    /**
     * Cancels the store's effects and builds a fresh store from its initial state, over the same clock, mocks and
     * device: `device relaunch`. The app's root shows it once [onRelaunch] has passed it on, and its views send their
     * own appearance.
     */
    private fun relaunch(): AgentStore<S, A> {
        environment.scope.cancel()
        environment = makeEnvironment()
        store = makeStore(environment)
        onRelaunch?.invoke(store)
        return store
    }

    /**
     * Whether the app's UI is idle: no navigation transition or animation in flight. [settle] waits for it as well as
     * for the store (CONTRACT.md §8.5), so a step after a push or a pop starts on the screen the user sees. Called on
     * [dispatcher]. `null`, the default, is a UI that is always idle; `agentctl-bridge` sets Compose's idleness unless
     * the host has set its own.
     */
    public var isUIIdle: (() -> Boolean)? = null

    /** Cancels every effect still running (its timers and mocked calls). The host is unusable afterwards. */
    public fun close() {
        scope.cancel()
    }

    /**
     * Settles on real time — no mock call in flight, the UI idle ([isUIIdle]), and the state quiet for a moment —
     * because a running app's latency, timers and transitions are real, unlike the headless host's. Call on
     * [dispatcher].
     */
    public suspend fun settle(): SettleResult = settle(waitingForUI = true)

    /** [settle], waiting for the UI only when [waitingForUI]: not while the store's screens are not shown (a seed). */
    internal suspend fun settle(waitingForUI: Boolean): SettleResult {
        val isUIIdle = isUIIdle.takeIf { waitingForUI }
        return settleLive(
            state = { store.state },
            callLog = callLog,
            pending = { clock.activeSleeps },
            quietWindow = 250.milliseconds,
            uiIdle = { isUIIdle?.invoke() ?: true },
        )
    }

    /**
     * The settling between two deadlines of an `advance`: long enough for what a timer fires to reach the store and
     * start its next sleep, short enough that `advance 1m` over a one-second countdown stays quick.
     */
    private suspend fun settleBetweenDeadlines() {
        settleLive(
            state = { store.state },
            callLog = callLog,
            pending = { clock.activeSleeps },
            quietWindow = 30.milliseconds,
            pollInterval = 5.milliseconds,
            limit = 1.seconds,
        )
    }

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
            // Synthesized appearance means no views show the store yet: there is no UI of it to wait for.
            settle = { settle(waitingForUI = !synthesizesAppearance) },
            advance = { duration -> clock.advance(duration.toDuration(), between = { settleBetweenDeadlines() }) },
            synthesizesAppearance = synthesizesAppearance,
        ),
        mockMethods = mockMethods,
        device = device,
        deviceConfig = deviceConfig,
        relaunch = ::relaunch,
    )
}
