package io.github.olbartek.agentctl

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
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
    private val sleeping = AtomicInteger(0)

    override fun now(): Instant = now.invoke()

    override val activeSleeps: Int get() = sleeping.get()

    override suspend fun sleep(duration: Duration) {
        sleeping.incrementAndGet()
        try {
            delay(duration)
        } finally {
            sleeping.decrementAndGet()
        }
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
