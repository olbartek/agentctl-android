package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.MockLatency
import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.runtime.BridgeResponse
import io.github.olbartek.agentctl.runtime.BridgeRouter
import io.github.olbartek.agentctl.runtime.BridgeServer
import io.github.olbartek.agentctl.runtime.HttpParser
import io.github.olbartek.agentctl.runtime.RunStatus
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * What this guards: a client that connects and then sends slowly, or nothing at all, holds up no other request; a
 * header block over the limit is malformed however it arrives; a handler that throws costs only its own request
 * (CONTRACT.md §8.1); and a request whose client goes away is cancelled, so the app stops running a script nobody
 * waits for and the next request does not wait behind it.
 */
class BridgeConnectionsTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val servers = mutableListOf<BridgeServer>()
    private val sockets = mutableListOf<Socket>()

    @AfterTest
    fun tearDown() {
        sockets.forEach { it.close() }
        servers.forEach { it.stop() }
        executor.shutdownNow()
    }

    private fun start(handler: suspend () -> BridgeResponse = { BridgeResponse(200, "ok\n", exitCode = 0) }): Pair<BridgeServer, Int> {
        val server = BridgeServer(executor.asCoroutineDispatcher(), "app", "android") { handler() }
        servers += server
        return server to server.start(0)
    }

    /** The status line of the answer to [request], or `""` when the bridge closed the connection without one. */
    private fun send(port: Int, request: String, timeoutMillis: Int = 3_000): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = timeoutMillis
        socket.getOutputStream().apply { write(request.toByteArray()); flush() }
        socket.getInputStream().readBytes().decodeToString().substringBefore("\r\n")
    }

    private fun idle(port: Int): Socket = Socket("127.0.0.1", port).also { sockets += it }

    private val get = "GET /snapshot HTTP/1.1\r\nHost: x\r\n\r\n"

    @Test
    fun anIdleConnectionHoldsUpNoOtherRequest() {
        val (_, port) = start()
        idle(port)
        val started = TimeSource.Monotonic.markNow()
        assertEquals("HTTP/1.1 200 OK", send(port, get))
        assertTrue(started.elapsedNow() < 2.seconds, "took ${started.elapsedNow()}")
    }

    @Test
    fun aSlowRequestIsIncompleteOnceItsDeadlinePasses() {
        val (server, port) = start()
        server.requestDeadline = 2.seconds
        val slow = idle(port).apply { soTimeout = 5_000 }
        val started = TimeSource.Monotonic.markNow()
        // A byte now and then: each read is quick, the request never ends.
        slow.getOutputStream().apply { write("GET /snap".toByteArray()); flush() }
        Thread.sleep(100)
        slow.getOutputStream().apply { write("shot".toByteArray()); flush() }
        val answer = slow.getInputStream().readBytes().decodeToString()
        assertTrue(answer.startsWith("HTTP/1.1 400"), answer)
        assertTrue(answer.endsWith("incomplete request\n"), answer)
        assertTrue(started.elapsedNow() < 5.seconds, "took ${started.elapsedNow()}")
    }

    @Test
    fun aHeaderBlockOverTheLimitIsMalformedEvenWhenItArrivesWhole() {
        val padding = "X-Pad: " + "a".repeat(HttpParser.MAX_HEADER_BYTES) + "\r\n"
        val request = "GET /snapshot HTTP/1.1\r\nHost: x\r\n$padding\r\n".toByteArray()
        assertEquals(HttpParser.Result.Malformed, HttpParser.parse(request))
        val atTheLimit = "GET / HTTP/1.1\r\nX: ".toByteArray().let { head ->
            head + ByteArray(HttpParser.MAX_HEADER_BYTES - head.size - 4) { 'a'.code.toByte() } + "\r\n\r\n".toByteArray()
        }
        assertEquals(HttpParser.MAX_HEADER_BYTES, atTheLimit.size)
        assertTrue(HttpParser.parse(atTheLimit) is HttpParser.Result.Request)
    }

    @Test
    fun aHandlerThatThrowsCostsOnlyItsOwnRequest() {
        var calls = 0
        val (_, port) = start {
            calls++
            if (calls == 1) throw IllegalStateException("a reducer failed")
            // A timeout in the app's code, say: cancellation, with no coroutine of the bridge's to cancel.
            if (calls == 2) throw CancellationException("timed out")
            BridgeResponse(200, "ok\n", exitCode = 0)
        }
        assertEquals("", send(port, get))
        assertEquals("", send(port, get))
        assertEquals("HTTP/1.1 200 OK", send(port, get))
    }

    /** Sends [request] and leaves the connection open, without reading the answer. */
    private fun sendAndKeep(port: Int, request: String): Socket = idle(port).apply {
        getOutputStream().apply { write(request.toByteArray()); flush() }
    }

    private fun post(script: String) = "POST /run HTTP/1.1\r\nHost: x\r\nContent-Length: ${script.length}\r\n\r\n$script"

    @Test
    fun aRequestWhoseClientGoesAwayIsCancelled() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        var calls = 0
        val (_, port) = start {
            if (++calls == 1) {
                started.countDown()
                try {
                    delay(30.seconds)
                } catch (e: CancellationException) {
                    cancelled.countDown()
                    throw e
                }
            }
            BridgeResponse(200, "ok\n", exitCode = 0)
        }
        val gone = sendAndKeep(port, get)
        assertTrue(started.await(3, TimeUnit.SECONDS), "the handler never started")
        gone.close()
        assertTrue(cancelled.await(3, TimeUnit.SECONDS), "the handler ran on after its client went away")
        val next = TimeSource.Monotonic.markNow()
        assertEquals("HTTP/1.1 200 OK", send(port, get))
        assertTrue(next.elapsedNow() < 2.seconds, "took ${next.elapsedNow()}")
    }

    @Test
    fun aRequestWhoseClientGoesAwayWhileQueuedNeverRuns() {
        val release = CountDownLatch(1)
        val ran = AtomicInteger(0)
        val first = CountDownLatch(1)
        val (_, port) = start {
            if (ran.incrementAndGet() == 1) {
                first.countDown()
                while (release.count > 0) delay(10)
            }
            BridgeResponse(200, "ok\n", exitCode = 0)
        }
        val waiting = sendAndKeep(port, get)
        assertTrue(first.await(3, TimeUnit.SECONDS))
        val queued = sendAndKeep(port, get)
        Thread.sleep(200)
        queued.close()
        Thread.sleep(200)
        release.countDown()
        waiting.soTimeout = 3_000
        assertTrue(waiting.getInputStream().readBytes().decodeToString().startsWith("HTTP/1.1 200 OK"))
        assertEquals("HTTP/1.1 200 OK", send(port, get))
        assertEquals(2, ran.get(), "the request whose client went away ran anyway")
    }

    @Test
    fun aClientThatWaitsGetsItsAnswerHoweverLongItTakes() {
        val (_, port) = start {
            delay(1_500)
            BridgeResponse(200, "ok\n", exitCode = 0)
        }
        assertEquals("HTTP/1.1 200 OK", send(port, get + "trailing bytes the bridge drops"))
    }

    /**
     * Against a live TinyApp: a run whose client goes away stops at its next line (no more mock calls), and leaves a
     * store that answers the next run.
     */
    @Test
    fun aCancelledRunStopsAndLeavesTheStoreUsable() {
        val dispatcher = executor.asCoroutineDispatcher()
        val app = runBlocking { withContext(dispatcher) { TinyAppConfig.live(MockLatency.milliseconds(200), dispatcher) } }
        val runner = app.makeRunner(synthesizesAppearance = true)
        runBlocking { withContext(dispatcher) { runner.launch() } }
        val router = BridgeRouter(runner) { "" }
        val server = BridgeServer(dispatcher, "app", "android") { router.handle(it) }
        servers += server
        val port = server.start(0)
        val gone = sendAndKeep(port, post(List(40) { "refresh" }.joinToString("; ")))
        Thread.sleep(700)
        gone.close()
        val answer = idle(port).run {
            soTimeout = 5_000
            getOutputStream().apply { write(post("open 2").toByteArray()); flush() }
            getInputStream().readBytes().decodeToString()
        }
        assertTrue(answer.startsWith("HTTP/1.1 200 OK"), answer)
        assertTrue("X-Appctl-Exit: 0\r\n" in answer && "screen=items/2" in answer, answer)
        val calls = app.callLog.count
        Thread.sleep(1_000)
        assertEquals(calls, app.callLog.count, "the cancelled run went on calling the mocks")
        app.close()
    }

    @Test
    fun aCancelledRunnerStopsBeforeItsNextLine() {
        val result = runBlocking {
            val runner = TinyAppConfig.headless().makeRunner()
            runner.launch()
            var outcome: io.github.olbartek.agentctl.runtime.RunResult? = null
            launch {
                coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                outcome = runner.run("open 2; back")
            }.join()
            outcome!!
        }
        assertEquals(RunStatus.INTERNAL_ERROR, result.status)
        assertEquals(emptyList(), result.steps)
        assertEquals("cancelled", result.message)
    }
}
