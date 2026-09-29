package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.every
import io.github.olbartek.agentctl.runtime.AdvanceableClock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * What this guards: the running app's clock, which `advance` moves forward (the reference's issue #2). Sleeps end
 * when the clock reaches their deadline, however it got there; a timer fires once per interval advanced; cancelling
 * still works.
 *
 * What it does not guard: the app around it, which [BridgeServerTest.advanceMovesTheRunningAppsClock] covers.
 */
class AdvanceableClockTest {
    private val scope = CoroutineScope(Dispatchers.Default)

    /** A long sleep on a fresh clock, and the clock, once the sleep has started. */
    private suspend fun sleeping(duration: Duration): Pair<AdvanceableClock, Deferred<Unit>> {
        val clock = AdvanceableClock()
        val sleep = scope.async { clock.sleep(duration) }
        until { clock.activeSleeps == 1 }
        return clock to sleep
    }

    /** Polls `condition` for up to five seconds of real time. */
    private suspend fun until(condition: () -> Boolean) {
        repeat(500) {
            if (condition()) return
            delay(10)
        }
        assertTrue(condition())
    }

    @Test
    fun advancingPastTheDeadlineEndsASleep() = runBlocking {
        val (clock, sleep) = sleeping(60.seconds)
        clock.advance(60.seconds)
        withTimeout(5.seconds) { sleep.await() }
        assertEquals(0, clock.activeSleeps)
        assertTrue(clock.offset >= 60.seconds)
    }

    @Test
    fun advancingShortOfTheDeadlineDoesNot() = runBlocking {
        val (clock, sleep) = sleeping(60.seconds)
        clock.advance(30.seconds)
        delay(100)
        assertEquals(1, clock.activeSleeps)
        clock.advance(30.seconds)
        withTimeout(5.seconds) { sleep.await() }
    }

    @Test
    fun aTimerTicksOncePerIntervalAdvanced() = runBlocking {
        val clock = AdvanceableClock()
        val ticks = AtomicInteger(0)
        val timer = scope.launch {
            clock.every(1.seconds) { ticks.incrementAndGet() }
        }
        until { clock.activeSleeps == 1 }
        // Between deadlines, a moment for the loop to start its next sleep.
        clock.advance(3.seconds, between = { delay(20.milliseconds) })
        assertEquals(3, ticks.get())
        timer.cancel()
    }

    @Test
    fun aCancelledSleepThrows() = runBlocking {
        val (clock, sleep) = sleeping(60.seconds)
        sleep.cancel()
        val thrown = runCatching { sleep.await() }.exceptionOrNull()
        assertTrue(thrown is CancellationException, "$thrown")
        until { clock.activeSleeps == 0 }
    }

    @Test
    fun nowMovesWithTheClock() = runBlocking {
        val clock = AdvanceableClock()
        clock.advance(3600.seconds)
        assertTrue(clock.now().epochSecond - Instant.now().epochSecond > 3590, "${clock.now()}")
        // Far beyond any deadline, the clock still reads a time.
        clock.advance(Duration.INFINITE)
        assertTrue(clock.now().isAfter(Instant.now()))
    }
}
