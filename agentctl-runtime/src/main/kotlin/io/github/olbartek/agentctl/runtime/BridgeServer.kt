package io.github.olbartek.agentctl.runtime

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * A tiny HTTP server on `127.0.0.1` (never another interface) that hands requests to a handler on the store's
 * dispatcher, one at a time, in arrival order (CONTRACT.md §8.1). On a device the CLI reaches it through
 * `adb forward`.
 */
public class BridgeServer(
    /** Where [handler] runs: the store's own thread. */
    private val dispatcher: CoroutineDispatcher,
    private val handler: suspend (BridgeRequest) -> BridgeResponse,
) {
    @Volatile private var socket: ServerSocket? = null

    /** Starts listening and returns the bound port (pass 0 for an ephemeral port). */
    @Throws(IOException::class)
    public fun start(port: Int): Int {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        socket = server
        thread(name = "AgentCtlBridge", isDaemon = true) { serve(server) }
        return server.localPort
    }

    public fun stop() {
        socket?.close()
        socket = null
    }

    private fun serve(server: ServerSocket) {
        while (!server.isClosed) {
            val connection = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            // One connection at a time: requests run in the order they arrive.
            connection.use { handle(it) }
        }
    }

    private fun handle(connection: Socket) {
        connection.soTimeout = READ_TIMEOUT_MILLIS
        val response = try {
            when (val parsed = read(connection)) {
                is HttpParser.Result.Request -> runBlocking { withContext(dispatcher) { handler(parsed.request) } }
                HttpParser.Result.Malformed -> BridgeResponse(400, "bad request\n", exitCode = RunStatus.USAGE.code)
                HttpParser.Result.Incomplete -> BridgeResponse(400, "incomplete request\n", exitCode = RunStatus.USAGE.code)
            }
        } catch (_: SocketTimeoutException) {
            BridgeResponse(400, "incomplete request\n", exitCode = RunStatus.USAGE.code)
        } catch (_: IOException) {
            return
        }
        try {
            connection.getOutputStream().apply {
                write(HttpParser.serialize(response))
                flush()
            }
        } catch (_: IOException) {
            // The client went away; nothing to answer.
        }
    }

    private fun read(connection: Socket): HttpParser.Result {
        val input = connection.getInputStream()
        var buffer = ByteArray(8 * 1024)
        var length = 0
        while (true) {
            if (length == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            val count = input.read(buffer, length, buffer.size - length)
            if (count < 0) return HttpParser.parse(buffer, length).let { if (it is HttpParser.Result.Request) it else HttpParser.Result.Incomplete }
            length += count
            val result = HttpParser.parse(buffer, length)
            if (result != HttpParser.Result.Incomplete) return result
        }
    }

    private companion object {
        const val READ_TIMEOUT_MILLIS = 30_000
    }
}
