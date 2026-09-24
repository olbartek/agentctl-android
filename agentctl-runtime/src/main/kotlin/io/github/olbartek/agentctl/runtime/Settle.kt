package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.MockCallLog
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/** The outcome of waiting for the app to settle after a command. */
public data class SettleResult(
    /** `false` when the real-time limit was reached before the app went quiet. */
    val settled: Boolean,
    /** Sleeps waiting on the clock (e.g. a countdown). Headlessly, `advance` releases them. */
    val pending: Int,
)

/** How many tasks one headless settling round runs before it looks at the app again. */
internal const val TASKS_PER_ROUND = 10_000

/**
 * Headless settling, which is what lets a step be read only once the app has gone quiet (CONTRACT.md §6): run the
 * due tasks and look again, until the state, the call log and the running effect count have not changed for
 * `stableRounds` rounds, or until `limit` of real time has passed.
 */
internal fun <S> settleHeadless(
    dispatcher: VirtualTimeDispatcher,
    state: () -> S,
    callLog: MockCallLog,
    effectsInFlight: () -> Int,
    pending: () -> Int,
    stableRounds: Int = 3,
    limit: Duration = 2.seconds,
): SettleResult {
    data class Fingerprint(val state: Any?, val calls: Int, val inFlight: Int)

    fun fingerprint() = Fingerprint(state(), callLog.count, effectsInFlight())

    val start = TimeSource.Monotonic.markNow()
    var last = fingerprint()
    var stable = 0
    while (stable < stableRounds) {
        if (start.elapsedNow() > limit) return SettleResult(settled = false, pending = pending())
        dispatcher.runDue(TASKS_PER_ROUND)
        val next = fingerprint()
        if (next == last && !dispatcher.hasDueTasks) {
            stable += 1
        } else {
            stable = 0
            last = next
        }
    }
    return SettleResult(settled = true, pending = pending())
}

/**
 * Live settling for the running app: wait until no mock call is in flight and the state has not changed for
 * `quietWindow`, or until `limit`. Mock latency is real here, so this uses real time. Must run on the store's
 * own dispatcher (the main thread), where the state is read.
 */
internal suspend fun <S> settleLive(
    state: () -> S,
    callLog: MockCallLog,
    pending: () -> Int,
    quietWindow: Duration = 100.milliseconds,
    pollInterval: Duration = 20.milliseconds,
    limit: Duration = 3.seconds,
): SettleResult {
    val start = TimeSource.Monotonic.markNow()
    var last = state()
    var quietSince = TimeSource.Monotonic.markNow()
    while (start.elapsedNow() < limit) {
        delay(pollInterval)
        val next = state()
        if (next != last || callLog.inFlight > 0) {
            last = next
            quietSince = TimeSource.Monotonic.markNow()
        } else if (quietSince.elapsedNow() >= quietWindow) {
            return SettleResult(settled = true, pending = pending())
        }
    }
    return SettleResult(settled = false, pending = pending())
}
