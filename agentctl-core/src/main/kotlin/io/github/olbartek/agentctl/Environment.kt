package io.github.olbartek.agentctl

import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope

/**
 * Everything an app's store and its mock clients may take from the outside world, handed to the host's store
 * factory. This is the Kotlin counterpart of the reference's pinned dependencies (CONTRACT.md §6): the headless
 * host fills every field deterministically, the live host and a release app with the system's own.
 *
 * An app that reads time, randomness, identifiers, the zone or the locale anywhere else is not deterministic
 * headlessly, and the CLI cannot make it so.
 */
public class AgentEnvironment(
    /** Where the store runs its effects: the virtual-time dispatcher headlessly, the main thread in the app. */
    public val scope: CoroutineScope,
    public val clock: AgentClock,
    /** The call log, faults and latency every mock client goes through ([MockBackend.call]). */
    public val mocks: MockBackend,
    /** New identifiers; headlessly `00000000-0000-0000-0000-000000000000`, then `…0001`, … */
    public val uuids: () -> UUID,
    public val random: Random,
    public val zone: ZoneId,
    public val locale: Locale,
) {
    public companion object {
        /**
         * The system's own values, for a release build (or any build without AgentCtl's hosts): real time, random
         * identifiers, the device's zone and locale, and [latency] for the mock clients.
         */
        public fun system(scope: CoroutineScope, latency: MockLatency = MockLatency.LIVE): AgentEnvironment {
            val clock = CountingClock.system()
            return AgentEnvironment(
                scope = scope,
                clock = clock,
                mocks = MockBackend(MockCallLog(), MockFaults(), latency, clock, Random.Default),
                uuids = { UUID.randomUUID() },
                random = Random.Default,
                zone = ZoneId.systemDefault(),
                locale = Locale.getDefault(),
            )
        }
    }
}

/** `00000000-0000-0000-0000-000000000000`, then `…0001`, …: the reference's `.incrementing` UUID generator. */
public class IncrementingUuids : () -> UUID {
    private var next = 0L

    @Synchronized
    override fun invoke(): UUID = UUID(0L, next++)
}

/**
 * A random number generator whose sequence is fixed by its seed, on every run and every platform: SplitMix64,
 * the reference's headless generator.
 */
public class SplitMix64Random(seed: Long) : Random() {
    private var state = seed

    @Synchronized
    override fun nextLong(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    override fun nextBits(bitCount: Int): Int =
        if (bitCount == 0) 0 else (nextLong() ushr (64 - bitCount)).toInt()
}
