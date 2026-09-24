package io.github.olbartek.agentctl.cli

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Talks to the agent bridge in the running app over loopback HTTP (through `adb forward` on a device). */
internal class BridgeClient(private val port: Int) {
    data class Response(val status: Int, val body: String, val exitCode: Int)

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
            return Response(status, text, exit)
        } finally {
            connection.disconnect()
        }
    }

    /** Polls `GET /snapshot` until the bridge answers, and returns the snapshot. */
    fun waitUntilReady(timeout: Duration = 60.seconds): String {
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < timeout) {
            try {
                val response = send("GET", "/snapshot", timeout = 2.seconds)
                if (response.status == 200) return response.body
            } catch (_: IOException) {
                // Not listening yet.
            }
            Thread.sleep(100)
        }
        throw AppCtlException("the app's agent bridge did not answer on 127.0.0.1:$port within $timeout")
    }
}
