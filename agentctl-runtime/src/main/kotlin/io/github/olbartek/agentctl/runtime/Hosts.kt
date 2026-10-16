package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.AgentContainer
import io.github.olbartek.agentctl.AgentCoordinator
import io.github.olbartek.agentctl.AgentDevice
import io.github.olbartek.agentctl.AgentDeviceConfig
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.AgentIntent
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
import kotlin.experimental.ExperimentalTypeInference
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * An app running in the CLI's process with the deterministic environment CONTRACT.md §6 requires, through its
 * [AgentDriver] ([AgentStoreDriver] or [AgentViewModelDriver]).
 *
 * It builds exactly this [AgentEnvironment] and hands it to the host's `makeDriver` (or `makeStore`, [AgentAppFactory] or
 * `coordinator`):
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
 * | `tasks` | counted on `scope`, for an MVVM app's view models |
 *
 * Nothing else is pinned: an app that reads `System.currentTimeMillis()`, `Locale.getDefault()` or
 * `Dispatchers.IO` around the environment is not deterministic headlessly, and `makeDriver` is where a host pins
 * whatever else it uses.
 *
 * Built by name for each architecture: `HeadlessHost(container, mockMethods, deviceConfig, makeStore)` for a store,
 * `HeadlessHost(mockMethods, deviceConfig) { AppCoordinator(it) }` for MVVM.
 */
public class HeadlessHost<D : AgentDriver<A, *>, A>(
    public val mockMethods: List<MockMethod>,
    /** What of the device scripts can drive, and the actions its events become (`device`). */
    public val deviceConfig: AgentDeviceConfig<A>,
    makeDriver: (AgentEnvironment) -> D,
) {
    /** Where every headless effect runs, one at a time in a fixed order, on virtual time that `advance` moves. */
    public val dispatcher: VirtualTimeDispatcher = VirtualTimeDispatcher()

    /** The host's scope. Each launch of the app runs its work in a child of it, which `device relaunch` cancels. */
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

    /** What the driver is given: the host's own scope, which outlives every launch, and everything above. */
    public val environment: AgentEnvironment = AgentEnvironment(
        scope = scope,
        clock = clock,
        mocks = MockBackend(callLog, faults, MockLatency.ZERO, clock, SplitMix64Random(RANDOM_SEED), device),
        uuids = IncrementingUuids(),
        random = SplitMix64Random(RANDOM_SEED),
        zone = FIXED_ZONE,
        locale = FIXED_LOCALE,
        device = device,
    )

    /** The app, as the engine sees it. */
    public val driver: D = makeDriver(environment)

    /** Runs the due work until the app has gone quiet (CONTRACT.md §6), or until settling's real-time limit passes. */
    public fun settle(): SettleResult = settleHeadless(
        dispatcher = dispatcher,
        fingerprint = driver::fingerprint,
        callLog = callLog,
        pending = { clock.activeSleeps },
    )

    /**
     * A [ScriptRunner] on this host's driver: it settles in virtual time, can `advance` the clock, and sends each
     * screen's appearance itself.
     */
    public fun makeRunner(): ScriptRunner<A> = ScriptRunner(
        driver = driver,
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

        /** UTC, the zone every headless run reads. */
        public val FIXED_ZONE: ZoneId = ZoneOffset.UTC

        /** `en_US_POSIX`, the locale that follows no user setting. */
        public val FIXED_LOCALE: Locale = Locale.Builder().setLanguage("en").setRegion("US").setVariant("POSIX").build()
    }
}

/**
 * A store app, headlessly: [app] builds its clients once per host and a store at each launch, so `device relaunch`
 * keeps what the clients stored.
 */
public fun <S, A> HeadlessHost(
    container: AgentContainer<S, A>,
    mockMethods: List<MockMethod>,
    deviceConfig: AgentDeviceConfig<A>,
    app: AgentAppFactory<S, A>,
): HeadlessHost<AgentStoreDriver<S, A>, A> = HeadlessHost(mockMethods, deviceConfig) { AgentStoreDriver(container, it, app) }

/**
 * A store app, headlessly, whose [makeStore] builds the whole app. `device relaunch` runs it again, so whatever it
 * builds starts over, clients included; an app whose clients keep something across a relaunch uses an [AgentAppFactory].
 */
public fun <S, A> HeadlessHost(
    container: AgentContainer<S, A>,
    mockMethods: List<MockMethod>,
    deviceConfig: AgentDeviceConfig<A>,
    makeStore: (AgentEnvironment) -> AgentStore<S, A>,
): HeadlessHost<AgentStoreDriver<S, A>, A> = HeadlessHost(container, mockMethods, deviceConfig, rebuilding(makeStore))

/** A store app, headlessly, without a device configuration: no `device` command. */
public fun <S, A> HeadlessHost(
    container: AgentContainer<S, A>,
    mockMethods: List<MockMethod>,
    makeStore: (AgentEnvironment) -> AgentStore<S, A>,
): HeadlessHost<AgentStoreDriver<S, A>, A> =
    HeadlessHost(container, mockMethods, AgentDeviceConfig.none(), rebuilding(makeStore))

/**
 * An MVVM app, headlessly: [coordinator] builds the app's coordinator on each launch's environment ([AgentViewModelDriver]):
 * `HeadlessHost(mockMethods, deviceConfig) { AppCoordinator(it) }`. A lambda that returns an [AgentDriver] instead
 * goes to the class's constructor, so a host's own driver is written the same way.
 */
@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
public fun <C : AgentCoordinator<C>> HeadlessHost(
    mockMethods: List<MockMethod>,
    deviceConfig: AgentDeviceConfig<AgentIntent<C>> = AgentDeviceConfig.none(),
    coordinator: (AgentEnvironment) -> C,
): HeadlessHost<AgentViewModelDriver<C>, AgentIntent<C>> =
    HeadlessHost(mockMethods, deviceConfig, makeDriver = { AgentViewModelDriver(it, coordinator) })

/** A store app's store. `device relaunch` replaces it. */
public val <S, A> HeadlessHost<AgentStoreDriver<S, A>, A>.store: AgentStore<S, A> get() = driver.store

/**
 * The app as the in-app bridge runs it in debug builds: real time and real mock latency, plus the hooks the agent
 * runtime needs (call log, faults, and a clock that counts pending sleeps), through its [AgentDriver].
 *
 * Time is real, but `advance` can move it forward: the environment's clock is an [AdvanceableClock], whose `now()`
 * moves with it. A backend of the host's own that reads the time or sleeps should do so on `environment.clock`, so
 * `advance` moves it too.
 *
 * @param dispatcher the app's thread: `Dispatchers.Main.immediate` in an Android app. The bridge runs every request on
 *   it.
 */
public class LiveHost<D : AgentDriver<A, *>, A>(
    public val mockMethods: List<MockMethod>,
    public val latency: MockLatency,
    public val dispatcher: CoroutineDispatcher,
    /** What of the device scripts can drive, and the actions its events become (`device`). */
    public val deviceConfig: AgentDeviceConfig<A>,
    makeDriver: (AgentEnvironment) -> D,
) {
    /** The host's scope. Each launch of the app runs its work in a child of it, which `device relaunch` cancels. */
    public val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** The app's clock: real time that `advance` moves forward, with its sleeps counted for `pending`. */
    public val clock: AdvanceableClock = AdvanceableClock()

    /** Every mock call, in order: what a step prints as `calls=`. */
    public val callLog: MockCallLog = MockCallLog()

    /** The one-shot failures `mock` arms. */
    public val faults: MockFaults = MockFaults()

    /** The simulated device, which `device` changes. */
    public val device: AgentDevice = AgentDevice(isDriven = true, offlineErrorCode = deviceConfig.offlineErrorCode)

    /** What the driver is given: the host's own scope, which outlives every launch, and everything above. */
    public val environment: AgentEnvironment = AgentEnvironment(
        scope = scope,
        clock = clock,
        mocks = MockBackend(callLog, faults, latency, clock, Random.Default, device),
        uuids = { UUID.randomUUID() },
        random = Random.Default,
        zone = ZoneId.systemDefault(),
        locale = Locale.getDefault(),
        device = device,
    )

    /** The app, as the engine sees it. Its `onRelaunch` tells the app's shell to show the new app. */
    public val driver: D = makeDriver(environment)

    /**
     * Whether the app's UI is idle: no navigation transition or animation in flight. [settle] waits for it as well as
     * for the app (CONTRACT.md §8.5), so a step after a push or a pop starts on the screen the user sees. Called on
     * [dispatcher]. `null`, the default, is a UI that is always idle; `agentctl-bridge` sets Compose's idleness unless
     * the host has set its own.
     */
    public var isUIIdle: (() -> Boolean)? = null

    /** Cancels every effect still running (its timers and mocked calls). The host is unusable afterwards. */
    public fun close() {
        scope.cancel()
    }

    /**
     * Settles on real time — no mock call in flight, the UI idle ([isUIIdle]), and the app quiet for a moment —
     * because a running app's latency, timers and transitions are real, unlike the headless host's. Call on
     * [dispatcher].
     */
    public suspend fun settle(): SettleResult = settle(waitingForUI = true)

    /** [settle], waiting for the UI only when [waitingForUI]: not while the app's screens are not shown (a seed). */
    internal suspend fun settle(waitingForUI: Boolean): SettleResult {
        val isUIIdle = isUIIdle.takeIf { waitingForUI }
        return settleLive(
            fingerprint = driver::fingerprint,
            callLog = callLog,
            pending = { clock.activeSleeps },
            quietWindow = 250.milliseconds,
            uiIdle = { isUIIdle?.invoke() ?: true },
        )
    }

    /**
     * The settling between two deadlines of an `advance`: long enough for what a timer fires to reach the app and
     * start its next sleep, short enough that `advance 1m` over a one-second countdown stays quick.
     */
    private suspend fun settleBetweenDeadlines() {
        settleLive(
            fingerprint = driver::fingerprint,
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
    public fun makeRunner(synthesizesAppearance: Boolean): ScriptRunner<A> = ScriptRunner(
        driver = driver,
        callLog = callLog,
        faults = faults,
        pending = { clock.activeSleeps },
        environment = RunnerEnvironment(
            // Synthesized appearance means no views show the app yet: there is no UI of it to wait for.
            settle = { settle(waitingForUI = !synthesizesAppearance) },
            advance = { duration -> clock.advance(duration.toDuration(), between = { settleBetweenDeadlines() }) },
            synthesizesAppearance = synthesizesAppearance,
        ),
        mockMethods = mockMethods,
        device = device,
        deviceConfig = deviceConfig,
    )
}

/**
 * A store app in the running app: [app] builds its clients once per host and a store at each launch, so
 * `device relaunch` keeps what the clients stored.
 */
public fun <S, A> LiveHost(
    container: AgentContainer<S, A>,
    mockMethods: List<MockMethod>,
    latency: MockLatency,
    dispatcher: CoroutineDispatcher,
    deviceConfig: AgentDeviceConfig<A>,
    app: AgentAppFactory<S, A>,
): LiveHost<AgentStoreDriver<S, A>, A> = LiveHost(mockMethods, latency, dispatcher, deviceConfig) { AgentStoreDriver(container, it, app) }

/** A store app in the running app, whose [makeStore] builds the whole app, again at each relaunch. */
public fun <S, A> LiveHost(
    container: AgentContainer<S, A>,
    mockMethods: List<MockMethod>,
    latency: MockLatency,
    dispatcher: CoroutineDispatcher,
    deviceConfig: AgentDeviceConfig<A>,
    makeStore: (AgentEnvironment) -> AgentStore<S, A>,
): LiveHost<AgentStoreDriver<S, A>, A> =
    LiveHost(container, mockMethods, latency, dispatcher, deviceConfig, rebuilding(makeStore))

/** A store app in the running app, without a device configuration: no `device` command. */
public fun <S, A> LiveHost(
    container: AgentContainer<S, A>,
    mockMethods: List<MockMethod>,
    latency: MockLatency,
    dispatcher: CoroutineDispatcher,
    makeStore: (AgentEnvironment) -> AgentStore<S, A>,
): LiveHost<AgentStoreDriver<S, A>, A> =
    LiveHost(container, mockMethods, latency, dispatcher, AgentDeviceConfig.none(), rebuilding(makeStore))

/**
 * An MVVM app in the running app: [coordinator] builds the coordinator on each launch's environment. As with
 * [HeadlessHost], a lambda that returns an [AgentDriver] goes to the class's constructor instead.
 */
@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
public fun <C : AgentCoordinator<C>> LiveHost(
    mockMethods: List<MockMethod>,
    latency: MockLatency,
    dispatcher: CoroutineDispatcher,
    deviceConfig: AgentDeviceConfig<AgentIntent<C>> = AgentDeviceConfig.none(),
    coordinator: (AgentEnvironment) -> C,
): LiveHost<AgentViewModelDriver<C>, AgentIntent<C>> =
    LiveHost(mockMethods, latency, dispatcher, deviceConfig, makeDriver = { AgentViewModelDriver(it, coordinator) })

/** A store app's store. `device relaunch` replaces it; the driver's `onRelaunch` hears of it. */
public val <S, A> LiveHost<AgentStoreDriver<S, A>, A>.store: AgentStore<S, A> get() = driver.store
