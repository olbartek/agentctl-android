package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.BridgeDefaults
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * A tiny HTTP server on `127.0.0.1` (never another interface) that hands requests to a handler on the store's
 * dispatcher, one at a time, in arrival order (CONTRACT.md §8.1). On a device the CLI reaches it through
 * `adb forward`.
 *
 * Each connection is read on its own, and only a complete request joins the queue, so a client that connects and
 * sends slowly, or nothing, holds up no one else: it gets `400 incomplete request` once [requestDeadline] passes. A
 * handler that throws costs only its own request: its connection closes unanswered, and the bridge keeps serving.
 */
public class BridgeServer(
    /** Where [handler] runs: the store's own thread. */
    private val dispatcher: CoroutineDispatcher,
    /** The app's application id, sent as `X-Appctl-App` on every response; `null` sends no such header. */
    private val appId: String? = null,
    /** The platform, sent as `X-Appctl-Platform` on every response; `null` sends no such header. */
    private val platform: String? = BridgeDefaults.PLATFORM,
    private val handler: suspend (BridgeRequest) -> BridgeResponse,
) {
    /** A server that sends no `X-Appctl-App`, as before 0.5 (kept so code built against 0.4 still links). */
    public constructor(dispatcher: CoroutineDispatcher, handler: suspend (BridgeRequest) -> BridgeResponse) :
        this(dispatcher, null, null, handler)

    /** How long a connection has, from its accept, to send its whole request. */
    @Volatile public var requestDeadline: Duration = 10.seconds

    @Volatile private var socket: ServerSocket? = null

    /** Answers complete requests one at a time, in the order they were completed; one per [start]. */
    @Volatile private var answering: ExecutorService? = null

    /** Connections still sending their request: each holds a reader thread until its deadline at most. */
    private val reading = Semaphore(MAX_READING)

    /** Starts listening and returns the bound port (pass 0 for an ephemeral port). */
    @Throws(IOException::class)
    public fun start(port: Int): Int {
        val server = ServerSocket()
        // CONTRACT.md §8.1: a port another socket listens on makes the bind fail, reuse or not (Linux never lets two
        // sockets listen on one port). Reuse lets a relaunch rebind over the TIME_WAIT connections of the bridge it
        // replaced, which would otherwise hold the port for a minute.
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        socket = server
        val answering = Executors.newSingleThreadExecutor { Thread(it, "AgentCtlBridge-answer").apply { isDaemon = true } }
        this.answering = answering
        thread(name = "AgentCtlBridge", isDaemon = true) { serve(server, answering) }
        return server.localPort
    }

    /** Stops listening. Requests already received are still answered; no new one is accepted. */
    public fun stop() {
        socket?.close()
        socket = null
        answering?.shutdown()
        answering = null
    }

    private fun serve(server: ServerSocket, answering: ExecutorService) {
        while (!server.isClosed) {
            val connection = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            // Past the limit, idle connections must not pile up threads: this one closes unanswered.
            if (!reading.tryAcquire()) {
                connection.close()
                continue
            }
            thread(name = "AgentCtlBridge-read", isDaemon = true) {
                val parsed = try {
                    read(connection)
                } catch (_: IOException) {
                    null
                } finally {
                    reading.release()
                }
                if (parsed == null) connection.close() else queue(answering, connection, parsed)
            }
        }
    }

    private fun queue(answering: ExecutorService, connection: Socket, parsed: HttpParser.Result) {
        try {
            answering.execute { connection.use { answer(it, parsed) } }
        } catch (_: RejectedExecutionException) {
            connection.close()
        }
    }

    private fun answer(connection: Socket, parsed: HttpParser.Result) {
        val response = when (parsed) {
            is HttpParser.Result.Request -> try {
                runBlocking { withContext(dispatcher) { handler(parsed.request) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The app's own code failed: this request gets no answer, the next ones do.
                System.err.println("AgentCtl bridge: ${parsed.request.method} ${parsed.request.path} failed")
                e.printStackTrace()
                return
            }
            HttpParser.Result.Malformed -> BridgeResponse(400, "bad request\n", exitCode = RunStatus.USAGE.code)
            HttpParser.Result.Incomplete -> BridgeResponse(400, "incomplete request\n", exitCode = RunStatus.USAGE.code)
        }
        try {
            connection.getOutputStream().apply {
                write(HttpParser.serialize(response, appId, platform))
                flush()
            }
        } catch (_: IOException) {
            // The client went away; nothing to answer.
        }
    }

    /** The request on [connection], [HttpParser.Result.Incomplete] if it is not all there by [requestDeadline]. */
    private fun read(connection: Socket): HttpParser.Result {
        val deadline = TimeSource.Monotonic.markNow() + requestDeadline
        val input = connection.getInputStream()
        var buffer = ByteArray(8 * 1024)
        var length = 0
        while (true) {
            if (length == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            val left = -deadline.elapsedNow()
            if (left <= Duration.ZERO) return HttpParser.Result.Incomplete
            connection.soTimeout = left.inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            val count = try {
                input.read(buffer, length, buffer.size - length)
            } catch (_: SocketTimeoutException) {
                return HttpParser.Result.Incomplete
            }
            if (count < 0) return HttpParser.parse(buffer, length).let { if (it is HttpParser.Result.Request) it else HttpParser.Result.Incomplete }
            length += count
            val result = HttpParser.parse(buffer, length)
            if (result != HttpParser.Result.Incomplete) return result
        }
    }

    private companion object {
        /** At most this many connections are sending their request at once. */
        const val MAX_READING = 16
    }
}
