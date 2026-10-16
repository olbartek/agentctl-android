package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.runtime.BridgeResponse
import io.github.olbartek.agentctl.runtime.BridgeServer
import io.github.olbartek.agentctl.runtime.HttpParser
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * What this guards: a client that connects and then sends slowly, or nothing at all, holds up no other request; a
 * header block over the limit is malformed however it arrives; and a handler that throws costs only its own request
 * (CONTRACT.md §8.1).
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
}
