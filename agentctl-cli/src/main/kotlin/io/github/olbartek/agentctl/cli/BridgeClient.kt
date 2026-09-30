package io.github.olbartek.agentctl.cli

import io.github.olbartek.agentctl.runtime.HttpParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Talks to the agent bridge in the running app over loopback HTTP (through `adb forward` on a device). */
internal class BridgeClient(private val port: Int) {
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

    @Throws(IOException::class)
    fun send(method: String, path: String, body: String? = null, timeout: Duration = 60.seconds): Response {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeout.inWholeMilliseconds.toInt()
            connection.readTimeout = timeout.inWholeMilliseconds.toInt()
            connection.setRequestProperty("Connection", "close")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
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
        } finally {
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
}
