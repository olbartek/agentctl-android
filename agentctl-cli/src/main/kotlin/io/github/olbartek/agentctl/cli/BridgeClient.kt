package io.github.olbartek.agentctl.cli

import io.github.olbartek.agentctl.AgentText
import io.github.olbartek.agentctl.AgentWaitArguments
import io.github.olbartek.agentctl.ExpectationSyntaxError
import io.github.olbartek.agentctl.ScriptError
import io.github.olbartek.agentctl.ScriptParser
import io.github.olbartek.agentctl.runtime.AgentLaunchOptions
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
 * take [scriptLimit] in all, or its `wait` lines' durations plus [WAIT_MARGIN] when they add up to more ([limit]);
 * every other request [IDLE_TIMEOUT] without an answer. Every request carries [token] in
 * `X-Appctl-Token`, when there is one.
 */
internal class BridgeClient(
    private val port: Int,
    private val token: String? = null,
    private val scriptLimit: Duration = Companion.scriptLimit,
) {
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
     * The bridge refused the request: `401` ([forbidden] false), it wants the launch's token; `403`, it does not
     * answer this request at all. [response] is the refusal, with the bridge's identity. An [IOException], so every
     * caller that reports an unreachable bridge catches it, and [Message.bridgeUnreachable] says this instead.
     */
    class Refused(val port: Int, val forbidden: Boolean, val response: Response) : IOException(
        "the app's agent bridge on 127.0.0.1:$port refused the " + if (forbidden) "request (forbidden)" else "token",
    )

    /**
     * Sends one request. [timeout] bounds a request that runs nothing, without an answer; `POST /run` is limited by
     * [limit] on the whole request instead, and throws [ScriptTimedOut] past it. A bridge's `401` or `403` (one
     * with `X-Appctl-Exit`) throws [Refused].
     */
    @Throws(IOException::class)
    fun send(method: String, path: String, body: String? = null, timeout: Duration = IDLE_TIMEOUT): Response {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        // The bridge answers a script only once it is done (CONTRACT.md §8.4), so one that waits on the app's real time
        // stays silent for as long as it runs: its limit is on the whole request, and generous.
        val runsScript = method == "POST" && path.startsWith("/run")
        val limit = if (runsScript) limit(body, scriptLimit) else scriptLimit
        val expired = AtomicBoolean(false)
        var connected = false
        val deadline = if (runsScript) {
            Timer("agentctl-run-limit", true).apply {
                // Bounded so that now plus the delay cannot overflow: a limit that long is no limit.
                schedule(limit.inWholeMilliseconds.coerceAtMost(Long.MAX_VALUE / 4)) {
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
            // 0, no read timeout, for a script limit past an Int's milliseconds: the timer above bounds it.
            connection.readTimeout = if (runsScript) {
                limit.inWholeMilliseconds.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: 0
            } else {
                timeout.inWholeMilliseconds.toInt()
            }
            // Connected explicitly: only a script that reached the bridge can have run past its limit. A connection
            // that times out never reached it, and stays "cannot reach".
            connection.setRequestProperty("Connection", "close")
            // Only a well-formed token goes out: nothing else (a line break, say) reaches the request's headers.
            token?.takeIf(AgentLaunchOptions::isToken)?.let { connection.setRequestProperty(HttpParser.TOKEN_HEADER, it) }
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
            val exitHeader = connection.getHeaderField("X-Appctl-Exit")
            val response = Response(
                status,
                text,
                exitHeader?.toIntOrNull() ?: 3,
                connection.getHeaderField(HttpParser.APP_HEADER),
                connection.getHeaderField(HttpParser.PLATFORM_HEADER),
            )
            if ((status == 401 || status == 403) && exitHeader != null) throw Refused(port, status == 403, response)
            return response
        } catch (error: Refused) {
            throw error
        } catch (error: IOException) {
            if (runsScript && connected && (expired.get() || error is SocketTimeoutException)) throw ScriptTimedOut(port, limit)
            throw error
        } finally {
            deadline?.cancel()
            connection.disconnect()
        }
    }

    /** Polls `GET /snapshot` until the bridge answers, and returns its answer: a refusal ([Refused.response]) too. */
    fun waitUntilReady(timeout: Duration = 60.seconds): Response {
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < timeout) {
            try {
                val response = send("GET", "/snapshot", timeout = 2.seconds)
                if (response.status == 200) return response
            } catch (refused: Refused) {
                return refused.response
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

        /**
         * What a script's `wait` lines are allowed on top of their durations: each may settle once more at its end, and
         * the request has its round trip (CONTRACT.md §8.6).
         */
        val WAIT_MARGIN: Duration = 60.seconds

        /**
         * How long `POST /run` with [script] may take: [base], or the durations of its `wait` lines plus [WAIT_MARGIN]
         * when they add up to more, so a wait never ends on the client's side first (CONTRACT.md §8.6).
         */
        fun limit(script: String?, base: Duration = scriptLimit): Duration {
            val lines = try {
                ScriptParser.parse(script ?: return base)
            } catch (_: ScriptError) {
                return base
            }
            val waits = lines.filter { AgentText.canonical(it.name) == "wait" }.mapNotNull { line ->
                try {
                    AgentWaitArguments.parse(line.argument).duration.toDuration()
                } catch (_: ExpectationSyntaxError) {
                    null
                }
            }
            if (waits.isEmpty()) return base
            return maxOf(base, waits.fold(Duration.ZERO, Duration::plus) + WAIT_MARGIN)
        }
    }
}
