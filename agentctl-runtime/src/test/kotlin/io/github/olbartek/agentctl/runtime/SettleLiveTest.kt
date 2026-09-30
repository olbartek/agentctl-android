package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.MockCallLog
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

/**
 * What this guards: live settling's timing rules for the UI (CONTRACT.md §8.5), on the settle loop itself, at the
 * poll interval the host uses and at a slow one like a loaded CI runner's, where polls land far apart. As
 * agentctl-ios's LiveSettleTests.
 */
class SettleLiveTest {
    private val pollIntervals = listOf(20.milliseconds, 60.milliseconds)
    private val quietWindow = 100.milliseconds

    private fun settle(pollInterval: Duration, state: () -> Any = { 0 }, uiIdle: () -> Boolean): SettleResult = runBlocking {
        settleLive(state, MockCallLog(), pending = { 0 }, quietWindow = quietWindow, pollInterval = pollInterval, uiIdle = uiIdle)
    }

    @Test
    fun aBusyUIHoldsSettlingUntilItIsIdle() {
        for (pollInterval in pollIntervals) {
            val start = TimeSource.Monotonic.markNow()
            val busyFor = 300.milliseconds
            val result = settle(pollInterval) { start.elapsedNow() >= busyFor }
            val elapsed = start.elapsedNow()
            assertTrue(result.settled, "at $pollInterval")
            // The quiet moment starts once the UI lets go, however far apart the polls are.
            assertTrue(elapsed >= busyFor + quietWindow, "at $pollInterval: settled after $elapsed")
        }
    }

    /**
     * A transition (0–80 ms) whose idle gap falls while the state changes (60–1200 ms, a slow response), and a second
     * one (1100–1500 ms) before the state goes quiet. Watching only quiet polls would miss the gap, date the second
     * transition from the first, call it endless and settle mid-animation.
     */
    @Test
    fun aSecondTransitionAfterALongStateChangeStillHolds() {
        for (pollInterval in pollIntervals) {
            val start = TimeSource.Monotonic.markNow()
            fun now(): Long = start.elapsedNow().inWholeMilliseconds
            val result = settle(pollInterval, state = { now().takeIf { it in 60..1199 } ?: 0L }) { now() >= 80 && now() !in 1100..1499 }
            assertTrue(result.settled, "at $pollInterval")
            assertTrue(now() >= 1500 + quietWindow.inWholeMilliseconds, "at $pollInterval: settled after ${now()} ms")
        }
    }

    /**
     * An endless animation (a spinner) is busy with short idle gaps between frames. After a second at a stretch it
     * stops holding settling. The gaps are counted in polls, not time: a time pattern can alias with slow polls.
     */
    @Test
    fun anEndlessAnimationStopsHoldingAfterASecond() {
        for (pollInterval in pollIntervals) {
            val start = TimeSource.Monotonic.markNow()
            var polls = 0
            val result = settle(pollInterval) { ++polls % 3 == 0 }
            val elapsed = start.elapsedNow()
            assertTrue(result.settled, "at $pollInterval")
            assertTrue(elapsed >= 1.seconds, "at $pollInterval: settled after $elapsed")
            assertTrue(elapsed < 2.seconds, "at $pollInterval: settled after $elapsed")
        }
    }

    /**
     * A chain of four mock calls, each in flight for about 0.9 s and started right after the one before (a sign-in's
     * requests at the default latency), settles once the last one has returned: past the old 3 s ceiling, within 10 s.
     */
    @Test
    fun aChainOfCallsLongerThanThreeSecondsSettles() {
        val calls = MockCallLog()
        val chain = Thread {
            repeat(4) { index ->
                calls.begin("auth.step$index")
                Thread.sleep(900)
                calls.end("auth.step$index")
                Thread.sleep(20)
            }
        }
        val start = TimeSource.Monotonic.markNow()
        calls.begin("auth.first")
        chain.start()
        calls.end("auth.first")
        val result = runBlocking { settleLive({ 0 }, calls, pending = { 0 }, quietWindow = 250.milliseconds) }
        val elapsed = start.elapsedNow()
        chain.join()
        assertTrue(result.settled, "gave up after $elapsed")
        assertTrue(elapsed >= 3.5.seconds, "settled mid-chain, after $elapsed")
    }

    /** A call that never returns does not hold settling for ever: the step gives up at the 10 s ceiling. */
    @Test
    fun aCallThatNeverReturnsGivesUpAtTenSeconds() {
        val calls = MockCallLog().apply { begin("auth.hangs") }
        val start = TimeSource.Monotonic.markNow()
        val result = runBlocking { settleLive({ 0 }, calls, pending = { 0 }, quietWindow = 250.milliseconds) }
        val elapsed = start.elapsedNow()
        assertFalse(result.settled)
        assertTrue(elapsed >= 10.seconds && elapsed < 11.seconds, "gave up after $elapsed")
    }
}
