package io.github.olbartek.agentctl

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.delay

/**
 * The app's source of time: what "now" is, and how an effect waits. Headlessly both are virtual — `now` is fixed
 * and `sleep` only returns when `advance` moves the clock — which is what makes a script's output reproducible
 * (CONTRACT.md §6). An app reads time only through this, never through `System.currentTimeMillis()` or a bare
 * `delay` outside its store's scope.
 */
public interface AgentClock {
    /** The current date and time. Headlessly, 2026-01-01T09:00:00Z on every read. */
    public fun now(): Instant

    /** Suspends for `duration` of this clock's time. */
    public suspend fun sleep(duration: Duration)

    /** Sleeps in progress right now: the `pending=` a step prints. */
    public val activeSleeps: Int
}

/**
 * Counts the sleeps currently waiting on it. The count is `pending` in step summaries: effects waiting for time
 * to pass, such as a countdown before a button becomes available again. Headlessly, `advance` releases them.
 * Counting sleeps (rather than all running effects) keeps long-lived effects out of the number.
 *
 * Its `sleep` is `delay`, so it runs on whatever clock the calling coroutine's dispatcher keeps: virtual time on
 * the headless host's dispatcher, real time on the main thread.
 */
public class CountingClock(private val now: () -> Instant) : AgentClock {
    private val next = AtomicLong(0)
    private val deadlines = ConcurrentHashMap<Long, Instant>()

    override fun now(): Instant = now.invoke()

    override val activeSleeps: Int get() = deadlines.size

    /**
     * The earliest deadline among the sleeps in progress, `null` when there are none. Headlessly, `wait` moves the
     * clock from one to the next (CONTRACT.md §2.5).
     */
    public val earliestDeadline: Instant? get() = deadlines.values.minOrNull()

    override suspend fun sleep(duration: Duration) {
        val id = next.incrementAndGet()
        deadlines[id] = deadline(duration)
        try {
            delay(duration)
        } finally {
            deadlines.remove(id)
        }
    }

    /**
     * When a sleep of [duration] ends: `now` plus it rounded up to a whole millisecond, as `delay` rounds it (an infinite
     * one saturates to `Long.MAX_VALUE` milliseconds), or `Instant.MAX` past it.
     */
    private fun deadline(duration: Duration): Instant = try {
        val milliseconds = if (duration.isPositive() && duration.isFinite()) {
            (duration + 999_999.nanoseconds).inWholeMilliseconds
        } else {
            duration.inWholeMilliseconds.coerceAtLeast(0)
        }
        now().plusMillis(milliseconds)
    } catch (_: java.time.DateTimeException) {
        Instant.MAX
    } catch (_: ArithmeticException) {
        Instant.MAX
    }

    public companion object {
        /** The system clock, for the running app. */
        public fun system(): CountingClock = CountingClock { Instant.now() }
    }
}

/** Runs `action` every `interval` of `clock`'s time until cancelled: a timer whose wait `pending=` counts. */
public suspend fun AgentClock.every(interval: Duration, action: suspend () -> Unit): Nothing {
    while (true) {
        sleep(interval)
        action()
    }
}
