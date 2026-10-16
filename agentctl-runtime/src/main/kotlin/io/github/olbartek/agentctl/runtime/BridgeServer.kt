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
 *
 * After an answer, a refusal included, the bridge ends its side of the connection, then drops whatever the client
 * still sends until it closes its own, for two seconds at most, so the answer is not lost to a reset (CONTRACT.md §8.1).
 *
 * **Who it answers.** A request is checked before it is queued, from its headers alone, and refused with the
 * connection closed: a malformed one, or one whose body would be over [HttpParser.MAX_BODY_BYTES], with `400 bad
 * request` (exit 2); one whose `Host` does not name the loopback (`127.0.0.1` or `localhost`, any port), that has no
 * `Host`, or that has an `Origin`, with `403 forbidden` (exit 3); and one without the expected token in
 * `X-Appctl-Token`, with `401 unauthorized` (exit 3): the launch's `agent-token`, as [AgentLaunchSession] passes it.
 * The constructors from before 0.9, deprecated until 1.0 removes them, make a server that checks every request but the
 * token (CONTRACT.md §8.2 has none: no token, no bridge).
 */
public class BridgeServer private constructor(
    /** The token every request must carry in `X-Appctl-Token` (compared in constant time); `null` checks none. */
    private val token: String?,
    /** Where [handler] runs: the store's own thread. */
    private val dispatcher: CoroutineDispatcher,
    /** The app's application id, sent as `X-Appctl-App` on every response; `null` sends no such header. */
    private val appId: String?,
    /** The platform, sent as `X-Appctl-Platform` on every response; `null` sends no such header. */
    private val platform: String?,
    private val handler: suspend (BridgeRequest) -> BridgeResponse,
) {
    /**
     * A server that answers only requests carrying [token] in `X-Appctl-Token` (compared in constant time): the
     * launch's `agent-token`. [dispatcher] is where [handler] runs, the store's own thread; [appId] and [platform] are
     * sent as `X-Appctl-App` and `X-Appctl-Platform` on every response (`null` sends no such header).
     */
    public constructor(
        dispatcher: CoroutineDispatcher,
        appId: String?,
        platform: String?,
        token: String,
        handler: suspend (BridgeRequest) -> BridgeResponse,
    ) : this(token, dispatcher, appId, platform, handler)

    /** A server that checks no token, as before 0.9 (kept so code built against 0.8 still links, until 1.0). */
    @Deprecated(
        "A bridge checks the launch's token (CONTRACT.md §8.2); pass it. Removed in 1.0.",
        ReplaceWith("BridgeServer(dispatcher, appId, platform, token, handler)"),
    )
    public constructor(
        dispatcher: CoroutineDispatcher,
        appId: String? = null,
        platform: String? = BridgeDefaults.PLATFORM,
        handler: suspend (BridgeRequest) -> BridgeResponse,
    ) : this(null, dispatcher, appId, platform, handler)

    /**
     * A server that sends no `X-Appctl-App` and checks no token, as before 0.5 (kept so code built against 0.4 still
     * links, until 1.0).
     */
    @Deprecated(
        "A bridge checks the launch's token (CONTRACT.md §8.2); pass it. Removed in 1.0.",
        ReplaceWith("BridgeServer(dispatcher, null, null, token, handler)"),
    )
    public constructor(dispatcher: CoroutineDispatcher, handler: suspend (BridgeRequest) -> BridgeResponse) :
        this(null, dispatcher, null, null, handler)

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

    /**
     * Answered or refused connections dropping what their client still sends on a thread of their own (not their
     * watcher's): each holds that thread for [LINGER] at most.
     */
    private val lingering = Semaphore(MAX_LINGERING)

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
            // Cleared first: the accept that closing wakes must already see that [stop] closed it.
            val listening = socket
            socket = null
            listening?.close()
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
                val read = try {
                    read(connection).also {
                        // Not routed, so not queued: answered here, while this connection still counts among those
                        // being read; it lingers once it no longer does.
                        if (it is Read.Refused) end(connection, it.response)
                    }
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
                when (read) {
                    null -> connection.close()
                    is Read.Refused -> linger(connection, TimeSource.Monotonic.markNow())
                    is Read.Routed -> {
                        val exchange = Exchange(connection)
                        // This thread then waits for the client to go away until the answer, and lingers after it.
                        // Past the limit, a request is answered without being watched.
                        if (queue(answering, exchange, read.request) && watching.tryAcquire()) watch(exchange)
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
        if (socket !== failed) return null // [stop] closed it meanwhile.
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
    private fun queue(answering: ExecutorService, exchange: Exchange, request: BridgeRequest): Boolean = try {
        answering.execute {
            val answered = try {
                answer(exchange, request)
            } catch (e: Throwable) {
                exchange.done()
                exchange.connection.close()
                throw e
            }
            when {
                !answered -> {
                    // Closed by the bridge, not its client: its watcher must not report the client gone.
                    exchange.done()
                    exchange.connection.close()
                }
                // Its watcher lingers and closes it: one thread reads a connection at a time.
                exchange.handOff() -> Unit
                else -> linger(exchange.connection, TimeSource.Monotonic.markNow())
            }
        }
        true
    } catch (_: RejectedExecutionException) {
        exchange.connection.close()
        false
    }

    /**
     * Holds a [watching] permit, and reads [exchange]'s connection until its client closes its side, which cancels its
     * request, or until its answer is written; then this thread lingers for it and closes it. The request was read
     * whole, so this reads only what comes after it: one request per connection, so it is dropped.
     *
     * It is the connection's only reader while it watches: on the JVM a second read waits for the first to return,
     * whatever its timeout, so the answer hands the lingering here rather than reading too. So it reads with a short
     * timeout ([WATCH_POLL]), to notice the answer.
     */
    private fun watch(exchange: Exchange) {
        val connection = exchange.connection
        val written = try {
            if (!exchange.startWatching()) return // Answered already: the answer's linger has it.
            try {
                connection.soTimeout = WATCH_POLL.inWholeMilliseconds.toInt()
                val input = connection.getInputStream()
                val scrap = ByteArray(1024)
                // Checked after every read too: a client that never stops sending never lets a read time out.
                while (!exchange.isHandedOff) {
                    val count = try {
                        input.read(scrap)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (count < 0) break
                }
            } catch (_: IOException) {
                // The client reset it.
            }
            exchange.stopWatching()
        } finally {
            watching.release()
        }
        if (written == null) {
            // Its answer, if it comes, lingers on its own.
            if (!exchange.isDone) events("client gone")
            exchange.abandon()
            return
        }
        connection.use { drain(it, written + LINGER) }
    }

    /** Handles [request] and writes its answer; `false` if there is none (cancelled, or the handler threw). */
    private fun answer(exchange: Exchange, request: BridgeRequest): Boolean {
        val connection = exchange.connection
        val response = try {
            runBlocking {
                // Started by await, after begin: if its client went away while it waited, it never starts.
                val handling = async(dispatcher, CoroutineStart.LAZY) { handler(request) }
                exchange.begin(handling)
                handling.await()
            }
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            if (e is CancellationException && exchange.isAbandoned) {
                System.err.println("AgentCtl bridge: ${request.method} ${request.path} cancelled: its client went away")
                events("cancelled")
                return false
            }
            // Cancellation included: there is no coroutine here to pass it on to, only this request to give up.
            System.err.println("AgentCtl bridge: ${request.method} ${request.path} failed")
            e.printStackTrace()
            return false
        }
        // Before the write: a client that reads its answer and closes is not one that went away.
        exchange.done()
        write(connection, response)
        try {
            connection.shutdownOutput()
        } catch (_: IOException) {
            // Gone already.
        }
        return true
    }

    /** Answers a request that is not routed with [response], and ends the bridge's side; [linger] closes it. */
    private fun end(connection: Socket, response: BridgeResponse) {
        write(connection, response)
        try {
            connection.shutdownOutput()
        } catch (_: IOException) {
            // Gone already.
        }
    }

    /**
     * Closes [connection], whose answer is written and whose side the bridge has ended ([since] then), once its client
     * has closed its own, or [LINGER] after [since] at most (CONTRACT.md §8.1): closing with the client's bytes unread
     * would reset the connection, and the reset can discard the answer before the client reads it. What the client
     * still sends (a body the bridge refused from its headers, say) is dropped. It lingers on a thread of its own, so
     * the next request is not kept waiting; past [MAX_LINGERING] such connections, one closes at once.
     */
    private fun linger(connection: Socket, since: TimeSource.Monotonic.ValueTimeMark) {
        if (!lingering.tryAcquire()) {
            connection.close()
            return
        }
        try {
            thread(name = "AgentCtlBridge-linger", isDaemon = true) {
                try {
                    connection.use { drain(it, since + LINGER) }
                } finally {
                    lingering.release()
                }
            }
        } catch (e: Throwable) {
            lingering.release()
            connection.close()
            if (e is VirtualMachineError) throw e
        }
    }

    /** Reads and drops what comes on [connection] until its client closes its side, or [deadline] at most. */
    private fun drain(connection: Socket, deadline: TimeSource.Monotonic.ValueTimeMark) {
        try {
            val input = connection.getInputStream()
            val scrap = ByteArray(8 * 1024)
            while (true) {
                val left = -deadline.elapsedNow()
                if (left <= Duration.ZERO) return
                connection.soTimeout = left.inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                if (input.read(scrap) < 0) return
            }
        } catch (_: IOException) {
            // Timed out, or gone already.
        }
    }

    private fun write(connection: Socket, response: BridgeResponse) {
        try {
            connection.getOutputStream().apply {
                write(HttpParser.serialize(response, appId, platform))
                flush()
            }
        } catch (_: IOException) {
            // The client went away; nothing to answer.
        }
    }

    /** What [read] makes of a connection: a request to route, or the answer it gets instead. */
    private sealed interface Read {
        data class Routed(val request: BridgeRequest) : Read

        data class Refused(val response: BridgeResponse) : Read
    }

    /**
     * The request on [connection], or its refusal: decided from the headers as soon as they are all there (a body over
     * the cap, another host, no token), so a refused request's body is never read; `400 incomplete request` if it is
     * not all there by [requestDeadline].
     */
    private fun read(connection: Socket): Read {
        val deadline = TimeSource.Monotonic.markNow() + requestDeadline
        val input = connection.getInputStream()
        var buffer = ByteArray(8 * 1024)
        var length = 0
        var checked = false
        while (true) {
            if (length == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            val left = -deadline.elapsedNow()
            if (left <= Duration.ZERO) return Read.Refused(INCOMPLETE)
            connection.soTimeout = left.inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            val count = try {
                input.read(buffer, length, buffer.size - length)
            } catch (_: SocketTimeoutException) {
                return Read.Refused(INCOMPLETE)
            }
            if (count > 0) length += count
            if (!checked) {
                when (val head = HttpParser.parseHead(buffer, length)) {
                    HttpParser.Head.Malformed -> return Read.Refused(MALFORMED)
                    HttpParser.Head.Incomplete -> Unit
                    is HttpParser.Head.Complete -> {
                        BridgeAccess.refusal(head.request, token)?.let { return Read.Refused(it) }
                        checked = true
                    }
                }
            }
            when (val result = HttpParser.parse(buffer, length)) {
                is HttpParser.Result.Request -> return Read.Routed(result.request)
                HttpParser.Result.Malformed -> return Read.Refused(MALFORMED)
                HttpParser.Result.Incomplete -> if (count < 0) return Read.Refused(INCOMPLETE)
            }
        }
    }

    /** One connection's request, from its queueing to its answer, and whether its client has gone away. */
    private class Exchange(val connection: Socket) {
        private var handling: Job? = null
        private var abandoned = false

        /** A thread is watching the connection: the answer hands it the lingering. */
        private var watched = false

        /** When the answer was written and the bridge's side ended; `null` until then. */
        private var written: TimeSource.Monotonic.ValueTimeMark? = null

        /**
         * Set once the answer is ready, or the bridge gives up on the request: nothing is left to cancel, and the
         * connection closing is not its client going away.
         */
        @Volatile var isDone = false
            private set

        val isAbandoned: Boolean get() = synchronized(this) { abandoned }

        val isHandedOff: Boolean get() = synchronized(this) { written != null }

        fun done() {
            isDone = true
        }

        /** `false` if the answer is already written: it lingers on its own, and nothing is left to watch. */
        fun startWatching(): Boolean = synchronized(this) {
            if (written != null) return false
            watched = true
            true
        }

        /** When the answer was written, if it was handed to this watcher to linger for; `null` if it was not. */
        fun stopWatching(): TimeSource.Monotonic.ValueTimeMark? = synchronized(this) {
            watched = false
            written
        }

        /** The answer is written: `true` if the watcher takes the lingering, `false` if the caller must. */
        fun handOff(): Boolean = synchronized(this) {
            written = TimeSource.Monotonic.markNow()
            watched
        }

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
        val MALFORMED = BridgeResponse(400, "bad request\n", exitCode = RunStatus.USAGE.code)
        val INCOMPLETE = BridgeResponse(400, "incomplete request\n", exitCode = RunStatus.USAGE.code)

        /**
         * How long an answered or refused connection is read and dropped from, at most, before it closes (CONTRACT.md
         * §8.1: a few seconds at most, so an answer is not lost to a reset however much the client still sends).
         */
        val LINGER: Duration = 2.seconds

        /** At most this many connections are sending their request at once. */
        const val MAX_READING = 16

        /** At most this many queued requests are watched for their client going away at once. */
        const val MAX_WATCHING = 16

        /** At most this many answered or refused connections linger on threads of their own at once. */
        const val MAX_LINGERING = 16

        /** How often a watcher stops reading to see whether the answer has been written. */
        val WATCH_POLL: Duration = 100.milliseconds
    }
}
