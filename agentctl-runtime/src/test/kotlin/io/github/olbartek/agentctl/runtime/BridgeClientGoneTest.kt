package io.github.olbartek.agentctl.runtime

import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay

/**
 * What this guards, on the bridge's own events: a request whose client goes away is cancelled, and reported as
 * cancelled (not as a failure), whether it was running or still queued. The end-to-end cases are in the `tests`
 * module's BridgeConnectionsTest.
 */
class BridgeClientGoneTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val servers = mutableListOf<BridgeServer>()
    private val sockets = mutableListOf<Socket>()
    private val events = CopyOnWriteArrayList<String>()
    private val clientsGone = AtomicInteger(0)

    @AfterTest
    fun tearDown() {
        sockets.forEach { it.close() }
        servers.forEach { it.stop() }
        executor.shutdownNow()
    }

    private val get = "GET /snapshot HTTP/1.1\r\nHost: x\r\n\r\n"

    private fun start(handler: suspend () -> BridgeResponse): Int {
        val server = BridgeServer(executor.asCoroutineDispatcher(), "app", "android") { handler() }
        server.events = { events += it; if (it == "client gone") clientsGone.incrementAndGet() }
        servers += server
        return server.start(0)
    }

    /** Sends [get] and leaves the connection open, without reading the answer. */
    private fun sendAndKeep(port: Int): Socket = Socket("127.0.0.1", port).also { sockets += it }.apply {
        getOutputStream().apply { write(get.toByteArray()); flush() }
    }

    private fun awaitClientsGone(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (clientsGone.get() < count) {
            check(System.nanoTime() < deadline) { "the bridge never saw the client go away: $events" }
            Thread.sleep(5)
        }
    }

    private fun awaitEvent(event: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (event !in events) {
            check(System.nanoTime() < deadline) { "no \"$event\": $events" }
            Thread.sleep(5)
        }
    }

    @Test
    fun aRequestWhoseClientGoesAwayWhileQueuedNeverRuns() {
        val release = CompletableDeferred<Unit>()
        val first = CountDownLatch(1)
        val ran = AtomicInteger(0)
        val port = start {
            if (ran.incrementAndGet() == 1) {
                first.countDown()
                release.await()
            }
            BridgeResponse(200, "ok\n", exitCode = 0)
        }
        val waiting = sendAndKeep(port)
        assertTrue(first.await(3, TimeUnit.SECONDS))
        // Watched only once queued: so its client going away is seen while it waits behind the first.
        sendAndKeep(port).close()
        awaitClientsGone(1)
        release.complete(Unit)
        waiting.soTimeout = 3_000
        assertTrue(waiting.getInputStream().readBytes().decodeToString().startsWith("HTTP/1.1 200 OK"))
        awaitEvent("cancelled")
        assertEquals(1, ran.get(), "the request whose client went away ran anyway")
    }

    @Test
    fun aRunningRequestWhoseClientGoesAwayIsReportedAsCancelled() {
        val started = CountDownLatch(1)
        val port = start {
            started.countDown()
            delay(30_000)
            BridgeResponse(200, "ok\n", exitCode = 0)
        }
        val gone = sendAndKeep(port)
        assertTrue(started.await(3, TimeUnit.SECONDS))
        gone.close()
        awaitEvent("cancelled")
        assertEquals(listOf("client gone", "cancelled"), events.toList())
    }
}
