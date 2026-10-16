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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * A tiny HTTP server on `127.0.0.1` (never another interface) that hands requests to a handler on the store's
 * dispatcher, one at a time, in arrival order (CONTRACT.md §8.1). On a device the CLI reaches it through
 * `adb forward`.
 *
 * Each connection is read on its own, and only a complete request joins the queue, so a client that connects and
 * sends slowly, or nothing, holds up no one else: it gets `400 incomplete request` once [requestDeadline] passes. A
 * handler that throws costs only its own request: its connection closes unanswered, and the bridge keeps serving.
 *
 * A request whose client goes away before its answer (the end of its stream says so: a client sends nothing after its
 * request) is cancelled, so a script nobody waits for stops at its next line and the next request does not wait
 * behind it. If accepting fails other than by [stop], the bridge says so on stderr (logcat in an app) and tries again
 * after a short wait: on the same socket while it is open (out of file descriptors, say), on a new one on the same port
 * once it has closed. After a few failures in a row it gives up and closes, so clients are refused rather than left
 * hanging.
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

    /** The listening socket; `null` once [stop] has closed it. Replaced under [lock] when the bridge listens again. */
    @Volatile private var socket: ServerSocket? = null
    private val lock = Any()

    /** Makes each listening socket; a test's may fail on purpose. */
    internal var makeSocket: () -> ServerSocket = ::ServerSocket

    /** How long the bridge waits after each failure to accept in a row; one more failure, and it gives up. */
    internal var relistenDelays: List<Duration> = listOf(100.milliseconds, 500.milliseconds, 2.seconds)

    /** What a test waits on: "client gone", "cancelled", "gave up". */
    internal var events: (String) -> Unit = {}

    /** Answers complete requests one at a time, in the order they were completed; one per [start]. */
    @Volatile private var answering: ExecutorService? = null

    /** Connections still sending their request: each holds a reader thread until its deadline at most. */
    private val reading = Semaphore(MAX_READING)

    /** Queued requests whose connection is watched for the client going away: each holds a thread until its answer. */
    private val watching = Semaphore(MAX_WATCHING)

    /** Starts listening and returns the bound port (pass 0 for an ephemeral port). */
    @Throws(IOException::class)
    public fun start(port: Int): Int {
        val server = listen(port)
        socket = server
        val answering = Executors.newSingleThreadExecutor { Thread(it, "AgentCtlBridge-answer").apply { isDaemon = true } }
        this.answering = answering
        thread(name = "AgentCtlBridge", isDaemon = true) { serve(server, answering) }
        return server.localPort
    }

    /** Stops listening. Requests already received are still answered; no new one is accepted. */
    public fun stop() {
        synchronized(lock) {
            socket?.close()
            socket = null
        }
        answering?.shutdown()
        answering = null
    }

    /** A socket listening on [port] of the loopback interface. */
    private fun listen(port: Int): ServerSocket {
        val server = makeSocket()
        try {
            // CONTRACT.md §8.1: a port another socket listens on makes the bind fail, reuse or not (Linux never lets
            // two sockets listen on one port). Reuse lets a relaunch rebind over the TIME_WAIT connections of the
            // bridge it replaced, which would otherwise hold the port for a minute.
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        } catch (e: IOException) {
            server.close()
            throw e
        }
        return server
    }

    private fun serve(listening: ServerSocket, answering: ExecutorService) {
        val port = listening.localPort
        var server = listening
        // Failures to accept in a row: each waits the next of [relistenDelays], and one past them gives up.
        var failures = 0
        while (true) {
            val connection = try {
                server.accept()
            } catch (first: IOException) {
                var error = first
                while (true) {
                    if (socket !== server) return // [stop] closed it.
                    if (failures == relistenDelays.size) {
                        giveUp(server, port, error)
                        return
                    }
                    val open = !server.isClosed
                    System.err.println(
                        "AgentCtl bridge: accepting on 127.0.0.1:$port failed ($error); " +
                            if (open) "trying again" else "listening again",
                    )
                    Thread.sleep(relistenDelays[failures++].inWholeMilliseconds)
                    // Still open: accept on it again. Closing it would drop the connections in its backlog.
                    if (open) break
                    server = try {
                        listenAgain(server, port) ?: return
                    } catch (e: IOException) {
                        error = e
                        continue
                    }
                    System.err.println("AgentCtl bridge: listening on 127.0.0.1:$port again")
                    break
                }
                continue
            }
            failures = 0
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
                } catch (e: VirtualMachineError) {
                    connection.close()
                    throw e
                } catch (_: Throwable) {
                    null
                } finally {
                    reading.release()
                }
                if (parsed == null) {
                    connection.close()
                } else {
                    val exchange = Exchange(connection)
                    // This thread then waits for the client to go away, until the answer closes the connection. Past
                    // the limit, a request is answered without being watched.
                    if (queue(answering, exchange, parsed) && parsed is HttpParser.Result.Request && watching.tryAcquire()) {
                        try {
                            watch(exchange)
                        } finally {
                            watching.release()
                        }
                    }
                }
            }
        }
    }

    /**
     * A new socket on [port] in place of [failed], which has closed; `null` if [stop] was called meanwhile. Throws if
     * the port cannot be bound.
     */
    @Throws(IOException::class)
    private fun listenAgain(failed: ServerSocket, port: Int): ServerSocket? {
        val fresh = listen(port)
        synchronized(lock) {
            if (socket !== failed) {
                fresh.close()
                return null
            }
            socket = fresh
        }
        return fresh
    }

    /** Closes [server] for good after one failure too many, so clients are refused rather than left in its backlog. */
    private fun giveUp(server: ServerSocket, port: Int, error: IOException) {
        synchronized(lock) {
            if (socket !== server) return
            server.close()
        }
        System.err.println("AgentCtl bridge: accepting on 127.0.0.1:$port failed ($error); giving up, the bridge is closed")
        events("gave up")
    }

    /** Queues [exchange]'s answer; `false` (the connection closed) once [stop] has shut the queue. */
    private fun queue(answering: ExecutorService, exchange: Exchange, parsed: HttpParser.Result): Boolean = try {
        answering.execute { exchange.connection.use { answer(exchange, parsed) } }
        true
    } catch (_: RejectedExecutionException) {
        exchange.connection.close()
        false
    }

    /**
     * Blocks until [exchange]'s client closes its side, then cancels its request; returns when the connection closes
     * either way. The request was read whole, so this reads only what comes after it: one request per connection, so
     * it is dropped.
     */
    private fun watch(exchange: Exchange) {
        val connection = exchange.connection
        try {
            connection.soTimeout = 0
            val input = connection.getInputStream()
            val scrap = ByteArray(1024)
            while (input.read(scrap) >= 0) {
                // Dropped.
            }
        } catch (_: IOException) {
            // The answer closed the connection, or the client reset it.
        }
        // Closed here: the answer is done, and there is nothing left to cancel.
        if (!connection.isClosed) events("client gone")
        exchange.abandon()
    }

    private fun answer(exchange: Exchange, parsed: HttpParser.Result) {
        val connection = exchange.connection
        val response = when (parsed) {
            is HttpParser.Result.Request -> try {
                runBlocking {
                    // Started by await, after begin: if its client went away while it waited, it never starts.
                    val handling = async(dispatcher, CoroutineStart.LAZY) { handler(parsed.request) }
                    exchange.begin(handling)
                    handling.await()
                }
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                if (e is CancellationException && exchange.isAbandoned) {
                    System.err.println("AgentCtl bridge: ${parsed.request.method} ${parsed.request.path} cancelled: its client went away")
                    events("cancelled")
                    return
                }
                // Cancellation included: there is no coroutine here to pass it on to, only this request to give up.
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

    /** One connection's request, from its queueing to its answer, and whether its client has gone away. */
    private class Exchange(val connection: Socket) {
        private var handling: Job? = null
        private var abandoned = false

        val isAbandoned: Boolean get() = synchronized(this) { abandoned }

        /** [handling] runs this request; it is cancelled at once if the client has already gone away. */
        fun begin(handling: Job) {
            synchronized(this) {
                this.handling = handling
                if (abandoned) handling.cancel()
            }
        }

        /** The client went away (or the answer is done): cancels the request if it is running. */
        fun abandon() {
            synchronized(this) {
                abandoned = true
                handling?.cancel()
            }
        }
    }

    private companion object {
        /** At most this many connections are sending their request at once. */
        const val MAX_READING = 16

        /** At most this many queued requests are watched for their client going away at once. */
        const val MAX_WATCHING = 16
    }
}
