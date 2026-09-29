package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.AgentClock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * The running app's clock: real time, which `advance` can move forward.
 *
 * Its time is how long it has run plus an [offset], and a sleep ends at its deadline in that time. Nothing moves the
 * offset but [advance], which takes it forward deadline by deadline, so every sleep due in the window ends, in
 * deadline order, and the app gets to react to each before the next one: a countdown that sleeps a second at a time
 * ticks once per second advanced, as it does on the headless host's virtual clock. [now] moves with it.
 *
 * Only what sleeps on this clock moves. A bare `delay`, a `Handler` or a `Timer` keeps real time, and so does
 * anything reading `System.currentTimeMillis()` instead of [now].
 *
 * It counts its sleeps for `pending=`, as [io.github.olbartek.agentctl.CountingClock] does headlessly.
 */
public class AdvanceableClock : AgentClock {
    private val start = TimeSource.Monotonic.markNow()
    private val lock = Any()
    private var offsetValue = Duration.ZERO
    private val sleepers = mutableSetOf<Sleeper>()
    private val sleeping = AtomicInteger(0)

    /** How far [advance] has moved this clock ahead of real time. */
    public val offset: Duration get() = synchronized(lock) { offsetValue }

    /** The wall-clock time moved by [offset]. */
    override fun now(): Instant = offset.toComponents { seconds, nanoseconds -> Instant.now().plusSeconds(seconds).plusNanos(nanoseconds.toLong()) }

    override val activeSleeps: Int get() = sleeping.get()

    override suspend fun sleep(duration: Duration) {
        // Counted and registered in one step, under the lock `advance` reads the sleeps under: once [activeSleeps]
        // counts a sleep, `advance` sees its deadline. (Counting first left a moment in which a timer that had just
        // started its next sleep was counted but not yet due, and `advance` moved past its deadline.)
        val deadline: Duration
        var waiting = synchronized(lock) {
            deadline = time() + duration
            sleeping.incrementAndGet()
            register(deadline)
        }
        try {
            while (waiting != null) {
                // Wait out the rest in real time, unless `advance` wakes this sleep first; then look at the time again.
                // Registering under the lock `advance` moves the offset under means a sleep either sees the new
                // offset or is among the sleeps that `advance` wakes.
                val (sleeper, remaining) = waiting
                try {
                    withTimeoutOrNull(remaining) { sleeper.woken.await() }
                } finally {
                    synchronized(lock) { sleepers.remove(sleeper) }
                }
                waiting = synchronized(lock) { register(deadline) }
            }
        } finally {
            sleeping.decrementAndGet()
        }
    }

    /** A sleeper for [deadline] and the real time left until it, or `null` once it has passed. Call under [lock]. */
    private fun register(deadline: Duration): Pair<Sleeper, Duration>? {
        val remaining = deadline - time()
        if (remaining <= Duration.ZERO) return null
        return Sleeper(deadline).also { sleepers.add(it) } to remaining
    }

    /**
     * Moves the clock forward by [duration]. It stops at each sleep's deadline in the window, earliest first, ends
     * the sleeps that are due and awaits [between] — the app settling — so what they start is already waiting on
     * the clock before it moves on.
     */
    public suspend fun advance(duration: Duration, between: suspend () -> Unit = {}) {
        val target = synchronized(lock) { (offsetValue + duration).coerceAtMost(MAX_OFFSET) }
        // A bound, in case a sleep keeps starting again with its deadline in the window.
        var rounds = 0
        while (rounds++ < 10_000) {
            val woken = synchronized(lock) {
                val wall = start.elapsedNow()
                val limit = wall + target
                val due = sleepers.filter { it.deadline <= limit }.minOfOrNull { it.deadline } ?: return@synchronized null
                // Never backwards: real time has passed since the offset was last set.
                offsetValue = maxOf(offsetValue, due - wall)
                // The due sleeps end; the others wake to wait out what is left of theirs, which is now less.
                val all = sleepers.toList()
                sleepers.removeAll { it.deadline <= due }
                all
            } ?: break
            woken.forEach(Sleeper::wake)
            yield()
            between()
        }
        val woken = synchronized(lock) {
            offsetValue = maxOf(offsetValue, target)
            sleepers.toList()
        }
        woken.forEach(Sleeper::wake)
        yield()
        between()
    }

    /** This clock's time: how long it has run, plus the offset. Deadlines are in it. Call under [lock]. */
    private fun time(): Duration = start.elapsedNow() + offsetValue

    /** One waiting sleep. [wake] ends the wait, whether it comes before, during or after it. */
    private class Sleeper(val deadline: Duration) {
        val woken = CompletableDeferred<Unit>()

        fun wake() {
            woken.complete(Unit)
        }
    }

    private companion object {
        /**
         * The furthest [advance] moves the clock: some 73 million years, which keeps [now] within [Instant]'s range.
         * No app can tell that the clock stops there.
         */
        val MAX_OFFSET: Duration = (Long.MAX_VALUE / 4).milliseconds
    }
}
