package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.MockLatency
import io.github.olbartek.agentctl.examples.tinyapp.Items
import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.examples.tinyapp.TinyRoot
import io.github.olbartek.agentctl.runtime.SettleResult
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * What this guards: live settling (CONTRACT.md §8.5) waits for the UI as well as the store. A screen still sliding in
 * or out is not settled, so a step after a push or a pop starts on the screen the user sees, not one half-way there.
 */
class LiveSettleTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    @AfterTest
    fun tearDown() {
        executor.shutdownNow()
    }

    /** Settles a live TinyApp whose UI reports [uiIdle], and how long that took. */
    private fun settle(uiIdle: (() -> Boolean)?): Pair<SettleResult, Duration> = runBlocking {
        withContext(dispatcher) {
            val app = TinyAppConfig.live(MockLatency.ZERO, dispatcher)
            app.isUIIdle = uiIdle
            val start = TimeSource.Monotonic.markNow()
            val result = app.settle()
            app.close()
            result to start.elapsedNow()
        }
    }

    /** Time since settling first asked the UI: a UI's busy stretches are timed from then. */
    private fun sinceFirstAsked(): () -> Duration {
        var start: TimeSource.Monotonic.ValueTimeMark? = null
        return { (start ?: TimeSource.Monotonic.markNow().also { start = it }).elapsedNow() }
    }

    @Test
    fun aQuietAppWithNoUiHookSettlesAfterTheQuietWindow() {
        val (result, took) = settle(uiIdle = null)
        assertTrue(result.settled)
        assertTrue(took < 1.seconds, "took $took")
    }

    @Test
    fun aTransitionInFlightHoldsSettlingUntilItEnds() {
        val busyFor = 600.milliseconds
        val elapsed = sinceFirstAsked()
        val (result, took) = settle(uiIdle = { elapsed() >= busyFor })
        assertTrue(result.settled)
        // The UI's busy time, then the whole quiet window after it: the quiet moment starts once the UI lets go, not
        // at the last poll that saw it busy. Timed on the UI's own clock, so a poll's slack cannot hide.
        val settledAt = elapsed()
        assertTrue(settledAt >= busyFor + 250.milliseconds, "settled at $settledAt")
        assertTrue(took < 2.seconds, "took $took")
    }

    /** A spinner on a settled screen animates without end: after a second it no longer holds settling. */
    @Test
    fun aUiThatNeverGoesIdleIsAnEndlessAnimationNotATransition() {
        val (result, took) = settle(uiIdle = { false })
        assertTrue(result.settled)
        assertTrue(took >= 1.seconds + 250.milliseconds, "took $took")
        assertTrue(took < 2.seconds, "took $took")
    }

    /** An endless animation is idle for a moment between frames: that does not make each frame a new transition. */
    @Test
    fun aUiIdleOnlyBetweenFramesIsStillAnEndlessAnimation() {
        val elapsed = sinceFirstAsked()
        // Idle for 20 ms of every 60: in time, not in polls, which a loaded machine spaces further apart.
        val (result, took) = settle(uiIdle = { elapsed().inWholeMilliseconds % 60 >= 40 })
        assertTrue(result.settled)
        assertTrue(took < 2.seconds, "took $took")
    }

    /** Each stretch of busy UI gets its own second: a new transition after an idle moment is waited for again. */
    @Test
    fun aSecondTransitionIsWaitedForAfterAnIdleMoment() {
        val elapsed = sinceFirstAsked()
        // Busy for 0.8 s, idle for 0.2 s, busy for another 0.8 s: 1.6 s of it, but never a second at a stretch.
        val (result, _) = settle(uiIdle = { elapsed().inWholeMilliseconds in 800..999 || elapsed() >= 1800.milliseconds })
        assertTrue(result.settled)
        val settledAt = elapsed()
        assertTrue(settledAt >= 1800.milliseconds + 250.milliseconds, "settled at $settledAt")
    }

    /**
     * The UI is watched on every poll, not only on quiet ones: a slow mocked call must not hide the idle moment between
     * two transitions, or the second would be dated from the first and taken for an endless animation.
     */
    @Test
    fun theUiIsWatchedWhileACallIsInFlight() {
        val took = runBlocking {
            withContext(dispatcher) {
                // `refresh` is a mocked call in flight from about 60 ms to 1200 ms.
                val app = TinyAppConfig.live(MockLatency.milliseconds(1140), dispatcher)
                val elapsed = sinceFirstAsked()
                // A transition at the start, and another from 1100 ms to 1500 ms.
                app.isUIIdle = { elapsed().inWholeMilliseconds !in 0..79 && elapsed().inWholeMilliseconds !in 1100..1499 }
                val start = TimeSource.Monotonic.markNow()
                launch {
                    delay(60.milliseconds)
                    app.store.send(TinyRoot.Action.Items(Items.Action.Refresh))
                }
                assertTrue(app.settle().settled)
                app.close()
                elapsed()
            }
        }
        assertTrue(took >= 1500.milliseconds + 250.milliseconds, "settled at $took")
    }

    /**
     * A launch seed runs before the store's screens are shown (the runner synthesizes their appearance), behind a
     * splash whose spinner is no transition of the app's: it does not wait for the UI. The bridge's runner does.
     */
    @Test
    fun onlyARunnerWhoseScreensAreShownWaitsForTheUi() {
        val busyFor = 700.milliseconds
        fun launch(synthesizesAppearance: Boolean): Duration = runBlocking {
            withContext(dispatcher) {
                val app = TinyAppConfig.live(MockLatency.ZERO, dispatcher)
                val elapsed = sinceFirstAsked()
                app.isUIIdle = { elapsed() >= busyFor }
                val start = TimeSource.Monotonic.markNow()
                assertTrue(app.makeRunner(synthesizesAppearance).launch().step.settled)
                app.close()
                if (synthesizesAppearance) start.elapsedNow() else elapsed()
            }
        }
        val seed = launch(synthesizesAppearance = true)
        assertTrue(seed < busyFor, "the seed took $seed")
        val bridge = launch(synthesizesAppearance = false)
        assertTrue(bridge >= busyFor + 250.milliseconds, "the bridge took $bridge")
    }

    @Test
    fun theUiIsAskedOnTheStoresThread() {
        val threads = mutableSetOf<Thread>()
        settle(uiIdle = { threads += Thread.currentThread(); true })
        val storeThread = runBlocking { withContext(dispatcher) { Thread.currentThread() } }
        assertEquals(setOf(storeThread), threads)
    }
}
