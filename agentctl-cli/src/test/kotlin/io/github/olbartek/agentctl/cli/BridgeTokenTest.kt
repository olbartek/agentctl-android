package io.github.olbartek.agentctl.cli

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What this guards: the launch's token, from `app launch` to every request. `app launch` passes a fresh one as the
 * `agent-token` extra and records it in `bridge.json`, which only its owner can read; every request sends it as
 * `X-Appctl-Token`, from `APPCTL_TOKEN` or else from the record for the port it connects to.
 */
class BridgeTokenTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private val state = BridgeState(
        platform = "android",
        device = "emulator-5556",
        port = 8766,
        appId = "dev.app",
        launchedAt = Instant.parse("2026-09-30T10:00:00Z"),
        token = token,
    )

    @Test
    fun theTokenIsRecordedAfterThePort() {
        assertEquals(
            """
            {
              "appId" : "dev.app",
              "device" : "emulator-5556",
              "launchedAt" : "2026-09-30T10:00:00Z",
              "platform" : "android",
              "port" : 8766,
              "token" : "$token"
            }
            """.trimIndent() + "\n",
            state.render(),
        )
        assertEquals(state, BridgeState.parse(state.render()))
        // A record from before 0.9 has none.
        assertNull(BridgeState.parse(state.copy(token = null).render())!!.token)
    }

    @Test
    fun theRecordIsTheOwnersAlone() {
        val root = Files.createTempDirectory("bridge-token").toFile()
        val layout = Layout(root, ".appctl")
        // Even over a record anyone could read.
        BridgeState.file(layout).apply { parentFile.mkdirs() }.writeText("{}")
        state.save(layout)
        val file = BridgeState.file(layout).toPath()
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file))
        assertEquals(state, BridgeState.load(layout))
        assertEquals(listOf("bridge.json"), File(root, ".appctl").list()!!.toList())
        root.deleteRecursively()
    }

    @Test
    fun theEnvironmentThenTheRecordForThatPort() {
        val other = "fedcba9876543210fedcba9876543210"
        assertEquals(token, Tokens.client(emptyMap(), 8766, state))
        assertEquals(other, Tokens.client(mapOf("APPCTL_TOKEN" to other), 8766, state))
        assertEquals(other, Tokens.client(mapOf("APPCTL_TOKEN" to other), 9000, null))
        // An empty APPCTL_TOKEN is unset, as an empty APPCTL_PORT is.
        assertEquals(token, Tokens.client(mapOf("APPCTL_TOKEN" to ""), 8766, state))
        // The record's token belongs to its port only.
        assertNull(Tokens.client(emptyMap(), 9000, state))
        assertNull(Tokens.client(emptyMap(), 8766, state.copy(token = null)))
        assertNull(Tokens.client(emptyMap(), 8766, null))
    }

    @Test
    fun aMalformedTokenIsNeverSent() {
        for (bad in listOf("abc", token.uppercase(), "$token\r\nX-Evil: 1", " $token")) {
            val error = assertFailsWith<Tokens.BadEnvironmentToken> { Tokens.client(mapOf("APPCTL_TOKEN" to bad), 8766, state) }
            assertEquals("APPCTL_TOKEN is not a token (32 lowercase hex characters)", error.message)
            // A malformed recorded token is no token.
            assertNull(Tokens.client(emptyMap(), 8766, state.copy(token = bad)))
            assertFalse(headOf(bad).contains("X-Appctl-Token", ignoreCase = true))
            assertFalse(headOf(bad).contains("X-Evil"))
        }
    }

    @Test
    fun theTokenNeverShowsInToString() {
        assertFalse(token in state.toString(), state.toString())
        assertTrue("token=<token>" in state.toString())
        val launched = AppLauncher.Launched(Device("emulator-5556", "avd", "16"), 8766, "launched", token)
        assertFalse(token in launched.toString(), launched.toString())
    }

    /** Two launches at once each write the record through a temporary file of their own: one whole record wins. */
    @Test
    fun concurrentSavesDoNotClash() {
        val root = Files.createTempDirectory("bridge-token").toFile()
        val layout = Layout(root, ".appctl")
        val states = (0 until 8).map { state.copy(port = 9000 + it) }
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = states.map { each -> Thread { repeat(20) { runCatching { each.save(layout) }.onFailure { failures.add(it) } } } }
        threads.forEach(Thread::start)
        threads.forEach { it.join(30_000) }
        assertEquals(emptyList(), failures.toList())
        assertTrue(BridgeState.load(layout) in states)
        assertEquals(listOf("bridge.json"), File(root, ".appctl").list()!!.toList())
        root.deleteRecursively()
    }

    /** A command a log records shows `<token>` for the launch's token, and the log is its owner's alone. */
    @Test
    fun logsHideTheToken() {
        assertEquals(
            "adb shell am start --es agent-token <token> --ei agent-port 8766",
            Shell.shown(listOf("adb", "shell", "am", "start", "--es", "agent-token", "'$token'", "--ei", "agent-port", "8766")),
        )
        val root = Files.createTempDirectory("bridge-token").toFile()
        val log = File(root, ".appctl/logs/app-launch.log")
        // Runs out of time, so its command line is logged.
        val status = Shell.run(listOf("sh", "-c", "sleep 5", "agent-token", token), root, log, timeoutSeconds = 1)
        assertEquals(Shell.NOT_RUN, status)
        val text = log.readText()
        assertTrue("agent-token <token> did not finish" in text, text)
        assertFalse(token in text, text)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(log.toPath()))
        root.deleteRecursively()
    }

    @Test
    fun eachLaunchPassesAFreshTokenAsAnExtra() {
        val extras = AppLauncher.launchExtras(port = 8766, token = token, seed = "open 'x'", latency = 0, clearSession = true)
        assertEquals(
            listOf(
                "--ei", "agent-port", "8766",
                "--es", "agent-token", "'$token'",
                "--es", "appctl-seed", "'open '\\''x'\\'''",
                "--ei", "mock-latency", "0",
                "--ez", "clear-session", "true",
            ),
            extras,
        )
        val tokens = (1..100).map { Tokens.make() }
        assertTrue(tokens.all { Regex("[0-9a-f]{32}").matches(it) }, tokens.toString())
        assertEquals(100, tokens.toSet().size)
    }

    /** Every request carries the token, when there is one; none is sent when there is not. */
    @Test
    fun theTokenTravelsInAHeader() {
        assertTrue(headOf(token).contains("\r\nX-Appctl-Token: $token\r\n"))
        assertFalse(headOf(null).contains("X-Appctl-Token", ignoreCase = true))
    }

    /** A bridge's 401 or 403 is a refusal, not an answer: the client says which, with the answer it refused with. */
    @Test
    fun aRefusalIsNotAnAnswer() {
        for ((status, forbidden) in listOf(401 to false, 403 to true)) {
            val refused = assertFailsWith<BridgeClient.Refused> { answeredWith(status, exit = 3) }
            assertEquals(forbidden, refused.forbidden)
            assertEquals("dev.app", refused.response.app)
        }
        // Without X-Appctl-Exit it is no bridge's refusal, but whatever answered there: an answer like any other.
        assertEquals(401, answeredWith(401, exit = null).status)
    }

    /** The head of the request a [BridgeClient] sends with [token]. */
    private fun headOf(token: String?): String = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
        var head = ""
        val reader = Thread {
            server.accept().use { connection ->
                val input = connection.getInputStream()
                val text = StringBuilder()
                while (!text.endsWith("\r\n\r\n")) text.append(input.read().toChar())
                head = text.toString()
                connection.getOutputStream().write(answer(200, 0))
            }
        }.apply { start() }
        BridgeClient(server.localPort, token).send("GET", "/snapshot")
        reader.join(5_000)
        head
    }

    private fun answeredWith(status: Int, exit: Int?): BridgeClient.Response = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
        val answering = Thread {
            server.accept().use { connection ->
                val input = connection.getInputStream()
                val text = StringBuilder()
                while (!text.endsWith("\r\n\r\n")) text.append(input.read().toChar())
                connection.getOutputStream().write(answer(status, exit))
            }
        }.apply { start() }
        try {
            BridgeClient(server.localPort, token).send("GET", "/snapshot")
        } finally {
            answering.join(5_000)
        }
    }

    private fun answer(status: Int, exit: Int?): ByteArray =
        ("HTTP/1.1 $status X\r\nContent-Length: 3\r\n" + (exit?.let { "X-Appctl-Exit: $it\r\n" } ?: "") +
            "X-Appctl-App: dev.app\r\nX-Appctl-Platform: android\r\nConnection: close\r\n\r\nno\n").toByteArray()
}
