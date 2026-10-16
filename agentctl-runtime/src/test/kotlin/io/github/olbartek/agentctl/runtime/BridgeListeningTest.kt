package io.github.olbartek.agentctl.runtime

import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * What this guards: a failure to accept other than by `stop()` does not leave the bridge bound but deaf. On a socket
 * still open (out of file descriptors, say) it accepts again on that socket, keeping its backlog; on one that closed
 * (the system took it back) it listens again on the same port. After a few failures in a row it gives up and closes,
 * so a client is refused rather than left waiting in the backlog. As agentctl-ios's listener restart.
 */
class BridgeListeningTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val servers = mutableListOf<BridgeServer>()
    private val made = AtomicInteger(0)
    private val gaveUp = CountDownLatch(1)

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop() }
        executor.shutdownNow()
    }

    /** A socket whose first accept closes it and fails, as one the system took back does. */
    private class ClosesOnce : ServerSocket() {
        private var failed = false

        override fun accept(): Socket {
            if (!failed) {
                failed = true
                close()
                throw SocketException("taken back")
            }
            return super.accept()
        }
    }

    /** A socket whose first [failures] accepts fail while it stays open, as when the app is out of file descriptors. */
    private class FailsWhileOpen(private var failures: Int) : ServerSocket() {
        override fun accept(): Socket {
            if (failures > 0) {
                failures--
                throw IOException("too many open files")
            }
            return super.accept()
        }
    }

    /** A socket that cannot bind, as when another app took the port meanwhile. */
    private class CannotBind : ServerSocket() {
        override fun bind(endpoint: java.net.SocketAddress?, backlog: Int): Unit = throw IOException("port taken")
    }

    /** A started server whose listening sockets [makeSocket] makes, given how many it made before; returns its port. */
    private fun start(makeSocket: (Int) -> ServerSocket): Int {
        val server = BridgeServer(executor.asCoroutineDispatcher(), "app", "android") { BridgeResponse(200, "ok\n", exitCode = 0) }
        server.makeSocket = { makeSocket(made.getAndIncrement()) }
        server.relistenDelays = listOf(10.milliseconds, 10.milliseconds, 10.milliseconds)
        server.events = { if (it == "gave up") gaveUp.countDown() }
        servers += server
        return server.start(0)
    }

    /** The status line of the answer to a GET. */
    private fun get(port: Int): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 3_000
        socket.getOutputStream().apply { write("GET /snapshot HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray()); flush() }
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

    private fun awaitGaveUp() = assertTrue(gaveUp.await(3, TimeUnit.SECONDS), "the bridge never gave up")

    @Test
    fun aClosedSocketListensAgainOnTheSamePort() {
        val port = start { if (it == 0) ClosesOnce() else ServerSocket() }
        assertEquals("HTTP/1.1 200 OK", getOnceListening(port))
        assertEquals("HTTP/1.1 200 OK", get(port))
        assertEquals(2, made.get())
    }

    @Test
    fun anOpenSocketThatFailsToAcceptIsAcceptedOnAgainNotReplaced() {
        val port = start { FailsWhileOpen(failures = 2) }
        // Connected while the accepts fail: it waits in the backlog, which a new socket would have dropped.
        assertEquals("HTTP/1.1 200 OK", get(port))
        assertEquals(1, made.get())
    }

    @Test
    fun anOpenSocketThatNeverAcceptsIsGivenUp() {
        val port = start { FailsWhileOpen(failures = Int.MAX_VALUE) }
        awaitGaveUp()
        assertFailsWith<ConnectException> { get(port) }
        assertEquals(1, made.get())
    }

    @Test
    fun aSocketThatCannotListenAgainLeavesTheBridgeClosed() {
        val port = start { if (it == 0) ClosesOnce() else CannotBind() }
        awaitGaveUp()
        assertFailsWith<ConnectException> { get(port) }
        assertEquals(1 + 3, made.get())
    }

    @Test
    fun stopDoesNotListenAgain() {
        val port = start { ServerSocket() }
        assertEquals("HTTP/1.1 200 OK", get(port))
        servers.single().stop()
        Thread.sleep(100)
        assertFailsWith<ConnectException> { get(port) }
        assertEquals(1, made.get())
    }
}
