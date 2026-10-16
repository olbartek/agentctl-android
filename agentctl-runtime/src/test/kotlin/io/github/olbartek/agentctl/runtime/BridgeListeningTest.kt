package io.github.olbartek.agentctl.runtime

import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * What this guards: a listening socket that fails other than by `stop()` (the system taking it back, say) does not
 * leave the bridge bound but deaf. It listens again on the same port, or, if it cannot, closes, so a client is refused
 * rather than left waiting in the backlog. As agentctl-ios's listener restart.
 */
class BridgeListeningTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val servers = mutableListOf<BridgeServer>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop() }
        executor.shutdownNow()
    }

    /** A socket whose first accept fails, as one the system took back does. */
    private class FailsOnce : ServerSocket() {
        private var failed = false

        override fun accept(): Socket {
            if (!failed) {
                failed = true
                throw SocketException("taken back")
            }
            return super.accept()
        }
    }

    /** A socket that cannot bind, as when another app took the port meanwhile. */
    private class CannotBind : ServerSocket() {
        override fun bind(endpoint: java.net.SocketAddress?, backlog: Int): Unit = throw IOException("port taken")
    }

    private fun server(makeSocket: (Int) -> ServerSocket): BridgeServer {
        val made = AtomicInteger(0)
        return BridgeServer(executor.asCoroutineDispatcher(), "app", "android") { BridgeResponse(200, "ok\n", exitCode = 0) }.apply {
            this.makeSocket = { makeSocket(made.getAndIncrement()) }
            relistenDelays = listOf(10.milliseconds, 10.milliseconds, 10.milliseconds)
            servers += this
        }
    }

    /** The status line of the answer to a GET, or the failure to connect. */
    private fun get(port: Int): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 3_000
        socket.getOutputStream().apply { write("GET /snapshot HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); flush() }
        socket.getInputStream().readBytes().decodeToString().substringBefore("\r\n")
    }

    /**
     * [get], retried for up to two seconds while the bridge is between sockets: refused, or reset with the socket
     * that failed.
     */
    private fun getOnceListening(port: Int): String {
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            try {
                return get(port)
            } catch (e: SocketException) {
                if (started.elapsedNow() > 2.seconds) throw e
                Thread.sleep(20)
            }
        }
    }

    @Test
    fun aFailedSocketListensAgainOnTheSamePort() {
        val server = server { made -> if (made == 0) FailsOnce() else ServerSocket() }
        val port = server.start(0)
        assertEquals("HTTP/1.1 200 OK", getOnceListening(port))
        assertEquals("HTTP/1.1 200 OK", get(port))
    }

    @Test
    fun aSocketThatCannotListenAgainLeavesTheBridgeClosed() {
        val server = server { made -> if (made == 0) FailsOnce() else CannotBind() }
        val port = server.start(0)
        val started = TimeSource.Monotonic.markNow()
        // Refused, not left hanging, once the bridge has given up (three short tries).
        while (true) {
            val refused = try {
                get(port)
                false
            } catch (_: ConnectException) {
                true
            } catch (_: SocketException) {
                // Reset with the socket that failed.
                false
            }
            if (refused) break
            check(started.elapsedNow() < 2.seconds) { "the bridge still accepts connections" }
            Thread.sleep(20)
        }
        Thread.sleep(100)
        assertFailsWith<ConnectException> { get(port) }
    }

    @Test
    fun stopDoesNotListenAgain() {
        val server = server { ServerSocket() }
        val port = server.start(0)
        assertEquals("HTTP/1.1 200 OK", get(port))
        server.stop()
        Thread.sleep(100)
        assertFailsWith<ConnectException> { get(port) }
    }
}
