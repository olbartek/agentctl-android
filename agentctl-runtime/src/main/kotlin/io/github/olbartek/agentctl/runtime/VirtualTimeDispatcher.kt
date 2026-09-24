package io.github.olbartek.agentctl.runtime

import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Runnable

/**
 * The headless host's scheduler: a single-threaded coroutine dispatcher with a virtual clock, the Kotlin
 * counterpart of the reference's `TestClock` on the main serial executor (CONTRACT.md §6).
 *
 * - Nothing runs until the host asks: [runDue] runs the tasks due now, one at a time, in the order
 *   `(time, sequence)`, on the calling thread. Every coroutine of the app's store therefore interleaves the same
 *   way on every run.
 * - `delay` (and `withTimeout`) on this dispatcher wait on virtual time. Only [advanceBy] moves it, releasing every
 *   timer whose deadline falls in the window, in deadline order, with the work each one starts in between.
 * - Tasks run one at a time rather than to exhaustion, so a restless app — one that never stops scheduling work —
 *   cannot trap the host: settling checks its real-time limit between batches.
 *
 * It is thread-safe to *dispatch* to (a leaked `withContext(Dispatchers.IO)` resumes here from another thread),
 * but tasks only ever run on the thread that calls [runDue] or [advanceBy].
 */
public class VirtualTimeDispatcher : CoroutineDispatcher(), Delay {
    private class Task(val time: Long, val sequence: Long, val block: Runnable) : Comparable<Task> {
        @Volatile var cancelled = false

        override fun compareTo(other: Task): Int =
            if (time != other.time) time.compareTo(other.time) else sequence.compareTo(other.sequence)
    }

    private val lock = Any()
    private val queue = PriorityQueue<Task>()
    private var sequence = 0L
    private var now = 0L

    /** Virtual milliseconds since the host started. */
    public val currentTime: Long get() = synchronized(lock) { now }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(lock) { enqueue(now, block) }
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val dispatcher = this
        val task = synchronized(lock) {
            enqueue(saturatingAdd(now, timeMillis)) { with(continuation) { dispatcher.resumeUndispatched(Unit) } }
        }
        continuation.invokeOnCancellation { task.cancelled = true }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val task = synchronized(lock) { enqueue(saturatingAdd(now, timeMillis), block) }
        return DisposableHandle { task.cancelled = true }
    }

    /** Runs up to `limit` tasks that are due now; returns how many ran. */
    public fun runDue(limit: Int = Int.MAX_VALUE): Int {
        var ran = 0
        while (ran < limit) {
            val task = synchronized(lock) { pollUpTo(now) } ?: break
            task.block.run()
            ran += 1
        }
        return ran
    }

    /** `true` if a task is due now. */
    public val hasDueTasks: Boolean get() = synchronized(lock) { peekUpTo(now) != null }

    /**
     * Moves the clock forward by `milliseconds` (saturating at `Long.MAX_VALUE`), running every task due within
     * the window in `(time, sequence)` order, the clock standing at each task's time while it runs.
     */
    public fun advanceBy(milliseconds: Long) {
        require(milliseconds >= 0) { "advanceBy($milliseconds): virtual time only moves forward" }
        val target = synchronized(lock) { saturatingAdd(now, milliseconds) }
        while (true) {
            val task = synchronized(lock) {
                pollUpTo(target)?.also { if (it.time > now) now = it.time }
            } ?: break
            task.block.run()
        }
        synchronized(lock) { if (target > now) now = target }
    }

    private fun enqueue(time: Long, block: Runnable): Task = Task(time, sequence++, block).also { queue.add(it) }

    private fun pollUpTo(time: Long): Task? {
        while (true) {
            val head = queue.peek() ?: return null
            if (head.cancelled) {
                queue.poll()
                continue
            }
            return if (head.time <= time) queue.poll() else null
        }
    }

    private fun peekUpTo(time: Long): Task? {
        while (true) {
            val head = queue.peek() ?: return null
            if (head.cancelled) {
                queue.poll()
                continue
            }
            return if (head.time <= time) head else null
        }
    }

    override fun toString(): String = "VirtualTimeDispatcher(time=${currentTime}ms)"

    private companion object {
        fun saturatingAdd(a: Long, b: Long): Long {
            val sum = a + b
            return if (b > 0 && sum < a) Long.MAX_VALUE else sum
        }
    }
}
