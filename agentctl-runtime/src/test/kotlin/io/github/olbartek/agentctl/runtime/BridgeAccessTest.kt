package io.github.olbartek.agentctl.runtime

import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * What this guards: who the bridge answers. Before anything is routed it refuses, in this order, a malformed request or
 * one whose body would be over 1 MiB (400, exit 2, decided from the headers alone), a request that does not name the
 * loopback as its host or that carries an `Origin` (403, exit 3), and one without the launch's token (401, exit 3).
 * Each refusal carries the app's identity, and none reaches the handler.
 */
class BridgeAccessTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val servers = mutableListOf<BridgeServer>()
    private val handled = AtomicInteger(0)

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop() }
        executor.shutdownNow()
    }

    /** A started bridge expecting [token] (`null`: one built without a token), answering `ok`; returns its port. */
    private fun start(token: String? = TOKEN): Int {
        val handler: suspend (BridgeRequest) -> BridgeResponse = {
            handled.incrementAndGet()
            BridgeResponse(200, "ok\n", exitCode = 0)
        }
        val server = if (token == null) {
            BridgeServer(executor.asCoroutineDispatcher(), "dev.app", "android", handler)
        } else {
            BridgeServer(executor.asCoroutineDispatcher(), "dev.app", "android", token, handler)
        }
        servers += server
        return server.start(0)
    }

    /** Sends [head] (lines, without the blank line that ends them) and [body], and returns the whole answer. */
    private fun exchange(port: Int, head: List<String>, body: String = ""): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write((head.joinToString("\r\n") + "\r\n\r\n" + body).toByteArray())
        socket.getOutputStream().flush()
        String(socket.getInputStream().readBytes())
    }

    private fun get(port: Int, vararg headers: String): String = exchange(port, listOf("GET /snapshot HTTP/1.1") + headers)

    private fun assertAnswer(status: String, body: String, exit: Int, answer: String) {
        assertTrue(answer.startsWith("HTTP/1.1 $status\r\n"), answer)
        assertTrue(answer.endsWith("\r\n\r\n$body"), answer)
        assertTrue("X-Appctl-Exit: $exit\r\n" in answer, answer)
        // Every refusal still says whose bridge it is (CONTRACT.md §8.4).
        assertTrue("X-Appctl-App: dev.app\r\nX-Appctl-Platform: android\r\n" in answer, answer)
    }

    private fun assertOk(answer: String) = assertAnswer("200 OK", "ok\n", 0, answer)

    private fun assertForbidden(answer: String) = assertAnswer("403 Forbidden", "forbidden\n", 3, answer)

    private fun assertUnauthorized(answer: String) = assertAnswer("401 Unauthorized", "unauthorized\n", 3, answer)

    @Test
    fun aBodyOverOneMebibyteIsRefusedFromItsHeaders() {
        val port = start()
        val start = TimeSource.Monotonic.markNow()
        // Only the headers are sent: the answer must not wait for a body.
        val answer = exchange(port, listOf("POST /run HTTP/1.1", "Host: 127.0.0.1:$port", "X-Appctl-Token: $TOKEN", "Content-Length: 1048577"))
        assertAnswer("400 Bad Request", "bad request\n", 2, answer)
        assertTrue(start.elapsedNow() < 5.seconds)
        assertEquals(0, handled.get())
    }

    @Test
    fun aBodyOfExactlyOneMebibyteIsRead() {
        assertEquals(HttpParser.Result.Malformed, HttpParser.parse("POST / HTTP/1.1\r\nContent-Length: 1048577\r\n\r\n".toByteArray()))
        assertEquals(HttpParser.Result.Incomplete, HttpParser.parse("POST / HTTP/1.1\r\nContent-Length: 1048576\r\n\r\n".toByteArray()))
        assertEquals(1_048_576, HttpParser.MAX_BODY_BYTES)
    }

    @Test
    fun theLoopbackByNameOrNumberIsAHost() {
        val port = start()
        for (host in listOf("127.0.0.1", "127.0.0.1:$port", "localhost", "LOCALHOST:9", "LocalHost:$port", "localhost:")) {
            assertOk(get(port, "Host: $host", "X-Appctl-Token: $TOKEN"))
        }
        assertEquals(6, handled.get())
    }

    @Test
    fun anyOtherHostIsForbidden() {
        val port = start()
        for (host in listOf(
            "evil.example", "evil.example:$port", "127.0.0.1.evil.example", "localhost.evil.example", "[::1]:$port", "::1",
            "127.0.0.2", "0.0.0.0", "", "localhost:abc", "LOCALHOſT", "127.0.0.1, evil.example",
        )) {
            assertForbidden(get(port, "Host: $host", "X-Appctl-Token: $TOKEN"))
        }
        assertForbidden(get(port, "X-Appctl-Token: $TOKEN"))
        // Two Host headers are not one loopback host.
        assertForbidden(get(port, "Host: 127.0.0.1", "Host: evil.example", "X-Appctl-Token: $TOKEN"))
        assertEquals(0, handled.get())
    }

    @Test
    fun anyOriginIsForbidden() {
        val port = start()
        for (origin in listOf("http://evil.example", "http://127.0.0.1:$port", "null", "")) {
            assertForbidden(get(port, "Host: 127.0.0.1:$port", "Origin: $origin", "X-Appctl-Token: $TOKEN"))
        }
        assertForbidden(get(port, "Host: 127.0.0.1:$port", "origin: http://localhost", "X-Appctl-Token: $TOKEN"))
        assertEquals(0, handled.get())
    }

    @Test
    fun aMissingOrWrongTokenIsUnauthorized() {
        val port = start()
        assertUnauthorized(get(port, "Host: 127.0.0.1:$port"))
        assertUnauthorized(get(port, "Host: 127.0.0.1:$port", "X-Appctl-Token: ${"0".repeat(32)}"))
        assertUnauthorized(get(port, "Host: 127.0.0.1:$port", "X-Appctl-Token: ${TOKEN.uppercase()}"))
        assertUnauthorized(get(port, "Host: 127.0.0.1:$port", "X-Appctl-Token: ${TOKEN}0"))
        assertUnauthorized(get(port, "Host: 127.0.0.1:$port", "X-Appctl-Token: "))
        assertUnauthorized(get(port, "Host: 127.0.0.1:$port", "X-Appctl-Token: $TOKEN", "X-Appctl-Token: $TOKEN"))
        assertEquals(0, handled.get())
        // The header's name in any case, as HTTP has it.
        assertOk(get(port, "Host: 127.0.0.1:$port", "x-appctl-token: $TOKEN"))
        assertOk(get(port, "Host: 127.0.0.1:$port", "X-Appctl-Token: $TOKEN"))
        assertEquals(2, handled.get())
    }

    @Test
    fun theChecksComeInOrder() {
        val port = start()
        // Over the cap and from another host, without a token: the cap is checked first.
        assertAnswer(
            "400 Bad Request", "bad request\n", 2,
            exchange(port, listOf("POST /run HTTP/1.1", "Host: evil.example", "Content-Length: 2000000")),
        )
        // From another host, without a token: the host is checked before the token.
        assertForbidden(get(port, "Host: evil.example"))
        assertForbidden(get(port, "Host: 127.0.0.1", "Origin: http://evil.example"))
        assertEquals(0, handled.get())
    }

    /** A refusal does not wait for a body it will not read: a foreign request is answered from its headers. */
    @Test
    fun aRefusalDoesNotWaitForTheBody() {
        val port = start()
        val start = TimeSource.Monotonic.markNow()
        assertForbidden(exchange(port, listOf("POST /run HTTP/1.1", "Host: evil.example", "Content-Length: 100")))
        assertUnauthorized(exchange(port, listOf("POST /run HTTP/1.1", "Host: 127.0.0.1", "Content-Length: 100")))
        assertTrue(start.elapsedNow() < 5.seconds)
    }

    /** A server built without a token (a JVM host's, a test's) checks no token; it still checks the host and the cap. */
    @Test
    fun aServerWithoutATokenChecksNoToken() {
        val port = start(token = null)
        assertOk(get(port, "Host: 127.0.0.1:$port"))
        assertForbidden(get(port, "Host: evil.example"))
        assertForbidden(get(port, "Host: 127.0.0.1", "Origin: http://evil.example"))
        assertAnswer(
            "400 Bad Request", "bad request\n", 2,
            exchange(port, listOf("POST /run HTTP/1.1", "Host: 127.0.0.1", "Content-Length: 1048577")),
        )
    }

    @Test
    fun theRequestCarriesItsHeadersByLowercaseName() {
        val parsed = HttpParser.parse("GET / HTTP/1.1\r\nHost: 127.0.0.1:9\r\nX-Appctl-Token:  abc \r\n\r\n".toByteArray())
        val request = (parsed as HttpParser.Result.Request).request
        assertEquals(mapOf("host" to "127.0.0.1:9", "x-appctl-token" to "abc"), request.headers)
    }

    @Test
    fun tokensAreComparedWhole() {
        assertTrue(BridgeAccess.tokenMatches(TOKEN, TOKEN))
        assertFalse(BridgeAccess.tokenMatches(TOKEN, null))
        assertFalse(BridgeAccess.tokenMatches(TOKEN, ""))
        assertFalse(BridgeAccess.tokenMatches(TOKEN, TOKEN.dropLast(1)))
        assertFalse(BridgeAccess.tokenMatches(TOKEN, TOKEN.dropLast(1) + "0"))
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef"
    }
}
