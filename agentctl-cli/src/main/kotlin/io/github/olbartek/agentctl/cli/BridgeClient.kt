package io.github.olbartek.agentctl.cli

import io.github.olbartek.agentctl.runtime.HttpParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Timer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Talks to the agent bridge in the running app over loopback HTTP (through `adb forward` on a device). `POST /run` may
 * take [scriptLimit] in all; every other request [IDLE_TIMEOUT] without an answer.
 */
internal class BridgeClient(private val port: Int, private val scriptLimit: Duration = Companion.scriptLimit) {
    /** [app] is the bridge's `X-Appctl-App`: the app it belongs to, or `null` from a bridge that does not say. */
    data class Response(val status: Int, val body: String, val exitCode: Int, val app: String? = null, val platform: String? = null) {
        /**
         * Whether this answer comes from [appId]'s bridge on [platform]: both headers, both equal. An app id without a
         * platform is a bridge that cannot say which build it is, and is not taken for this one (CONTRACT.md §8.4).
         */
        fun isFrom(appId: String, platform: String): Boolean = app == appId && this.platform == platform

        /**
         * Whether this answer is not from [recorded] (`bridge.json`'s) app and platform. A header the answer lacks is a
         * mismatch too: the launch that recorded them was answered with both, as the same build always answers
         * (CONTRACT.md §8.6).
         */
        fun contradicts(recorded: BridgeState): Boolean = !isFrom(recorded.appId, recorded.platform)
    }

    /**
     * `POST /run` that did not finish within its limit: the bridge was reached, and the app may still be running the
     * script (as agentctl-ios's `BridgeClient.ScriptTimedOut`). An [IOException], so every caller that reports an
     * unreachable bridge catches it, and [Message.bridgeUnreachable] says this instead.
     */
    class ScriptTimedOut(val port: Int, val limit: Duration) : IOException(
        "the app did not finish the script within ${limit.inWholeSeconds} s (agent bridge on 127.0.0.1:$port); it may still be running it",
    )

    /**
     * Sends one request. [timeout] bounds a request that runs nothing, without an answer; `POST /run` is limited by
     * [scriptLimit] on the whole request instead, and throws [ScriptTimedOut] past it.
     */
    @Throws(IOException::class)
    fun send(method: String, path: String, body: String? = null, timeout: Duration = IDLE_TIMEOUT): Response {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        // The bridge answers a script only once it is done (CONTRACT.md §8.4), so one that waits on the app's real time
        // stays silent for as long as it runs: its limit is on the whole request, and generous.
        val runsScript = method == "POST" && path.startsWith("/run")
        val expired = AtomicBoolean(false)
        var connected = false
        val deadline = if (runsScript) {
            Timer("agentctl-run-limit", true).apply {
                schedule(scriptLimit.inWholeMilliseconds) {
                    expired.set(true)
                    connection.disconnect()
                }
            }
        } else {
            null
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeout.inWholeMilliseconds.toInt()
            connection.readTimeout = (if (runsScript) scriptLimit else timeout).inWholeMilliseconds.toInt()
            // Connected explicitly: only a script that reached the bridge can have run past its limit. A connection
            // that times out never reached it, and stays "cannot reach".
            connection.setRequestProperty("Connection", "close")
            val bytes = body?.toByteArray(Charsets.UTF_8)
            if (bytes != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
            }
            connection.connect()
            connected = true
            bytes?.let { connection.outputStream.use { stream -> stream.write(it) } }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            // A missing header means whatever answered is not an AgentCtl bridge: an internal error (CONTRACT.md §8.4).
            val exit = connection.getHeaderField("X-Appctl-Exit")?.toIntOrNull() ?: 3
            return Response(
                status,
                text,
                exit,
                connection.getHeaderField(HttpParser.APP_HEADER),
                connection.getHeaderField(HttpParser.PLATFORM_HEADER),
            )
        } catch (error: IOException) {
            if (runsScript && connected && (expired.get() || error is SocketTimeoutException)) throw ScriptTimedOut(port, scriptLimit)
            throw error
        } finally {
            deadline?.cancel()
            connection.disconnect()
        }
    }

    /** Polls `GET /snapshot` until the bridge answers, and returns its answer. */
    fun waitUntilReady(timeout: Duration = 60.seconds): Response {
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < timeout) {
            try {
                val response = send("GET", "/snapshot", timeout = 2.seconds)
                if (response.status == 200) return response
            } catch (_: IOException) {
                // Not listening yet.
            }
            Thread.sleep(100)
        }
        throw AppCtlException("the app's agent bridge did not answer on 127.0.0.1:$port within $timeout")
    }

    companion object {
        /** How long a request that runs nothing (`GET`, the readiness poll) may go without an answer. */
        val IDLE_TIMEOUT: Duration = 60.seconds

        /** How long `POST /run` may take in all, as agentctl-ios's `BridgeClient.scriptLimit`. */
        val SCRIPT_LIMIT: Duration = 3600.seconds

        /** The limit a new client gets: [SCRIPT_LIMIT], shorter only in a test. */
        @Volatile
        var scriptLimit: Duration = SCRIPT_LIMIT
    }
}
