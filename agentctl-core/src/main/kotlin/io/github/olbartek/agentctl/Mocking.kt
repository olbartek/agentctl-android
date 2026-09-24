package io.github.olbartek.agentctl

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Records every mock call as `"<client>.<method>"`, e.g. `items.fetch`, and how many are still running.
 *
 * Agents see the entries as `calls=` in step summaries and assert on them with `expect call=…`. The agent bridge
 * waits for `inFlight == 0` when settling a step in the running app.
 */
public class MockCallLog {
    private val lock = Any()
    private val entries = mutableListOf<String>()
    private var running = 0

    public fun begin(name: String): Unit = synchronized(lock) {
        entries.add(name)
        running += 1
    }

    @Suppress("UNUSED_PARAMETER")
    public fun end(name: String): Unit = synchronized(lock) { running -= 1 }

    public val all: List<String> get() = synchronized(lock) { entries.toList() }
    public val count: Int get() = synchronized(lock) { entries.size }
    public val inFlight: Int get() = synchronized(lock) { running }

    /** Entries recorded after the first `index` entries. */
    public fun since(index: Int): List<String> = synchronized(lock) { entries.drop(index) }
}

/**
 * One-shot forced failures keyed by `"<client>.<method>"`.
 *
 * `mock <client.method> <error>` registers `<error>` for `<client.method>`; the next call to that method throws the
 * matching error and the fault is cleared.
 */
public class MockFaults {
    private val lock = Any()
    private val faults = mutableMapOf<String, String>()

    public fun set(method: String, code: String): Unit = synchronized(lock) { faults[method] = code }

    /** Returns and clears the fault registered for `method`, if any. */
    public fun take(method: String): String? = synchronized(lock) { faults.remove(method) }

    public val pending: Map<String, String> get() = synchronized(lock) { faults.toMap() }
}

/**
 * How long mock backends pretend to take. [LIVE] (300–800 ms) in the app, zero headlessly and in tests.
 * When it is zero, mocks never touch the clock.
 */
public data class MockLatency(val range: ClosedRange<Duration>?) {
    /** A delay drawn from the range, or zero. The generator is only used when the range is not a single value. */
    public fun sample(random: Random): Duration {
        val range = range ?: return Duration.ZERO
        val lower = range.start.inWholeMilliseconds
        val upper = range.endInclusive.inWholeMilliseconds
        if (upper <= lower) return range.start
        return random.nextLong(lower, upper + 1).milliseconds
    }

    public companion object {
        public val ZERO: MockLatency = MockLatency(null)
        public val LIVE: MockLatency = between(300.milliseconds, 800.milliseconds)

        public fun fixed(duration: Duration): MockLatency = MockLatency(duration..duration)

        public fun between(lower: Duration, upper: Duration): MockLatency = MockLatency(lower..upper)

        public fun milliseconds(milliseconds: Int): MockLatency =
            if (milliseconds <= 0) ZERO else fixed(milliseconds.milliseconds)
    }
}

/** A mock method that can be made to fail with `mock <name> <code>`: its name and the error codes it accepts. */
public data class MockMethod(val name: String, val errorCodes: List<String>)

/**
 * The single shim every mock backend method goes through: the Kotlin counterpart of the reference's `mockCall`.
 * A host builds its mock clients on the [AgentEnvironment.mocks] it is given, so the headless host, the live
 * host and the release app each hand it the right log, faults, latency and clock.
 */
public class MockBackend(
    public val log: MockCallLog,
    public val faults: MockFaults,
    public val latency: MockLatency,
    public val clock: AgentClock,
    public val random: Random,
) {
    /**
     * Records the call, waits the [latency] (never touching the clock when it is zero), throws a fault registered
     * in [faults] if there is one, and otherwise runs `body`.
     *
     * @param name `"<client>.<method>"`, e.g. `items.fetch`.
     * @param error turns a fault code (e.g. `network`) into the client's own exception.
     */
    public suspend fun <T> call(name: String, error: (String) -> Throwable, body: suspend () -> T): T {
        log.begin(name)
        try {
            val delay = latency.sample(random)
            if (delay > Duration.ZERO) clock.sleep(delay)
            faults.take(name)?.let { throw error(it) }
            return body()
        } finally {
            log.end(name)
        }
    }
}
