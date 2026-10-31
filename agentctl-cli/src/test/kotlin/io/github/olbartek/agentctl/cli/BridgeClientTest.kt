package io.github.olbartek.agentctl.cli

import io.github.olbartek.agentctl.DocsText
import io.github.olbartek.agentctl.runtime.AgentLaunchOptions
import io.github.olbartek.agentctl.runtime.AgentStoreDriver
import io.github.olbartek.agentctl.runtime.BridgeResponse
import io.github.olbartek.agentctl.runtime.BridgeServer
import io.github.olbartek.agentctl.runtime.AppCtlConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay

/**
 * What this guards: `POST /run` is limited by a generous limit on the whole request, not by the 60 s that every other
 * request gets without an answer. The bridge answers a script only once it is done (CONTRACT.md §8.4), so a script
 * past that limit reached the bridge and says so, rather than that the bridge cannot be reached.
 *
 * The bridge here accepts connections and never answers; the limit is a second.
 */
class BridgeClientTest {
    private val silent = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val held = mutableListOf<Socket>()
    private val acceptor = Thread {
        try {
            while (true) held += silent.accept()
        } catch (_: IOException) {
            // Closed by the test.
        }
    }.apply {
        isDaemon = true
        start()
    }

    @AfterTest
    fun closeTheBridge() {
        silent.close()
        acceptor.join(1000)
        held.forEach { it.close() }
    }

    @Test
    fun aScriptHasAnHourOtherRequestsAMinute() {
        assertEquals(3600.seconds, BridgeClient.SCRIPT_LIMIT)
        assertEquals(60.seconds, BridgeClient.IDLE_TIMEOUT)
    }

    @Test
    fun aScriptPastItsLimitDidNotFinishRatherThanNotReachTheBridge() {
        val error = assertFailsWith<BridgeClient.ScriptTimedOut> {
            BridgeClient(silent.localPort, scriptLimit = 1.seconds).send("POST", "/run", "open 2")
        }
        assertEquals(
            "the app did not finish the script within 1 s (agent bridge on 127.0.0.1:${silent.localPort}); it may still be running it",
            error.message,
        )
        val unreachable = Message.bridgeUnreachable(cli(), silent.localPort, error)
        assertEquals(error.message, unreachable)
    }

    @Test
    fun aRequestThatRunsNothingKeepsItsIdleTimeout() {
        val error = assertFailsWith<IOException> {
            BridgeClient(silent.localPort, scriptLimit = 1.seconds).send("GET", "/snapshot", timeout = 300.milliseconds)
        }
        assertFalse(error is BridgeClient.ScriptTimedOut)
    }

    /**
     * A connection that is never accepted (a listener whose backlog is full) did not reach the bridge: the script
     * never started, so it is "cannot reach", not "did not finish".
     */
    @Test
    fun aScriptWhoseConnectionTimesOutDidNotReachTheBridge() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { full ->
            val queued = mutableListOf<Socket>()
            try {
                // Fill the backlog: once a connection is not taken, the next ones are not answered at all.
                repeat(8) {
                    val socket = Socket()
                    try {
                        socket.connect(java.net.InetSocketAddress(full.inetAddress, full.localPort), 300)
                        queued += socket
                    } catch (_: IOException) {
                        socket.close()
                    }
                }
                val error = assertFailsWith<IOException> {
                    BridgeClient(full.localPort, scriptLimit = 30.seconds).send("POST", "/run", "open 2", timeout = 500.milliseconds)
                }
                assertFalse(error is BridgeClient.ScriptTimedOut, "$error")
            } finally {
                queued.forEach { it.close() }
            }
        }
    }

    @Test
    fun appRunSaysTheScriptDidNotFinish() {
        val previous = BridgeClient.scriptLimit
        BridgeClient.scriptLimit = 1.seconds
        val err = ByteArrayOutputStream()
        val status = try {
            AgentCtl.run(
                config,
                listOf("app", "run", "--port", "${silent.localPort}", "open 2"),
                PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8),
                PrintStream(err, true, Charsets.UTF_8),
                environment = emptyMap(),
                workingDirectory = Files.createTempDirectory("bridge-client").toFile(),
            )
        } finally {
            BridgeClient.scriptLimit = previous
        }
        assertEquals(3, status)
        assertEquals(
            "error: the app did not finish the script within 1 s (agent bridge on 127.0.0.1:${silent.localPort}); it may still be running it\n",
            err.toString(Charsets.UTF_8),
        )
    }

    /**
     * A `wait` keeps `POST /run` silent for as long as it waits, so the client allows a script its waits plus a margin
     * whenever that is longer than its usual limit (CONTRACT.md §8.6).
     */
    @Test
    fun aScriptIsAllowedItsWaits() {
        assertEquals(10.seconds, BridgeClient.limit("open 1", 10.seconds))
        assertEquals(65.seconds, BridgeClient.limit("wait 5s screen=a", 10.seconds))
        assertEquals((120 + 30 + 10 + 60).seconds, BridgeClient.limit("wait 2m; wait 30s; wait", 10.seconds))
        assertEquals(61500.milliseconds, BridgeClient.limit("wait 1500ms", 10.seconds))
        assertEquals(10.seconds, BridgeClient.limit("wait \"open", 10.seconds))
        assertEquals(7260.seconds, BridgeClient.limit("wait 2h", BridgeClient.SCRIPT_LIMIT))
    }

    /**
     * The claim above, end to end: a bridge that takes its `wait`'s three seconds to answer, reached through a client
     * whose script limit and idle timeout are far below that, answers (exit 1, the app's own timeout) rather than
     * being cut off on the client's side.
     */
    @Test
    fun aWaitIsNotCutOffByTheClient() {
        val token = AgentLaunchOptions.makeToken()
        val executor = Executors.newSingleThreadExecutor()
        val server = BridgeServer(executor.asCoroutineDispatcher(), "x", "android", token) { request ->
            delay(3.seconds)
            BridgeResponse(200, "> ${request.body}\n  FAIL wait timed out after 3s\n", exitCode = 1)
        }
        try {
            val port = server.start(0)
            val start = TimeSource.Monotonic.markNow()
            val answer = BridgeClient(port, token, scriptLimit = 1.seconds).send("POST", "/run", "wait 3s screen=nowhere", timeout = 1.seconds)
            assertTrue(start.elapsedNow() >= 3.seconds)
            assertEquals(1, answer.exitCode)
            assertTrue(answer.body.endsWith("FAIL wait timed out after 3s\n"), answer.body)
        } finally {
            server.stop()
            executor.shutdownNow()
        }
    }

    private val config = AppCtlConfig<AgentStoreDriver<Unit, Unit>, Unit>(
        name = "X",
        applicationId = "x",
        mockMethods = emptyList(),
        docsText = DocsText("X", "", emptyList(), appendix = emptyList()),
        screens = emptyList(),
        makeHeadless = { error("not headless") },
        makeLive = { _, _ -> error("not live") },
    )

    private fun cli() = Cli(config, Io(System.out, System.err), emptyMap(), Files.createTempDirectory("bridge-client").toFile())
}
