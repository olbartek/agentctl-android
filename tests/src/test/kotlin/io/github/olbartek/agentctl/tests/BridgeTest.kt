package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.BridgeDefaults
import io.github.olbartek.agentctl.MockLatency
import io.github.olbartek.agentctl.ScreensRenderer
import io.github.olbartek.agentctl.StepFormatter
import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.runtime.AgentLaunchOptions
import io.github.olbartek.agentctl.runtime.AgentLaunchSession
import io.github.olbartek.agentctl.runtime.BridgeRequest
import io.github.olbartek.agentctl.runtime.BridgeResponse
import io.github.olbartek.agentctl.runtime.BridgeRouter
import io.github.olbartek.agentctl.runtime.BridgeServer
import io.github.olbartek.agentctl.runtime.HttpParser
import io.github.olbartek.agentctl.runtime.RunStatus
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * What this guards: `POST /run`'s two forms carry the same information, driven directly on a headless runner — no
 * server, no network.
 */
class BridgeRouterTest {
    private fun post(script: String, json: Boolean): BridgeResponse = runBlocking {
        val runner = TinyAppConfig.headless().makeRunner()
        runner.launch()
        BridgeRouter(runner) { "" }.handle(BridgeRequest("POST", "/run", if (json) mapOf("format" to "json") else emptyMap(), script))
    }

    @Test
    fun aParseErrorKeepsItsReasonInTheJsonForm() {
        val response = post("expect \"open", json = true)
        assertEquals(2, response.exitCode)
        assertEquals("application/json", response.contentType)
        assertEquals(
            "{\n  \"error\" : \"parse error: line 1, column 8: unterminated quote\",\n  \"steps\" : [\n\n  ]\n}",
            response.body,
        )
    }

    @Test
    fun aParseErrorInTheTextFormIsTheLineRunPrintsToStderr() {
        val response = post("expect \"open", json = false)
        assertEquals(2, response.exitCode)
        assertEquals("error: parse error: line 1, column 8: unterminated quote\n", response.body)
    }

    @Test
    fun aRunWithStepsIsStillAnArray() {
        for ((script, exit) in listOf("expect screen=items" to 0, "expect screen=nope" to 1)) {
            val response = post(script, json = true)
            assertEquals(exit, response.exitCode)
            assertTrue(response.body.startsWith("[\n  {\n"), response.body)
            assertTrue(response.body.contains("\"ok\" : ${exit == 0}"), response.body)
        }
    }

    @Test
    fun methodsAndPaths() = runBlocking {
        val runner = TinyAppConfig.headless().makeRunner()
        runner.launch()
        val router = BridgeRouter(runner) { "screens" }
        assertEquals(405, router.handle(BridgeRequest("GET", "/run")).status)
        assertEquals("method not allowed\n", router.handle(BridgeRequest("DELETE", "/state")).body)
        val missing = router.handle(BridgeRequest("GET", "/nope"))
        assertEquals(404, missing.status)
        assertEquals(2, missing.exitCode)
        assertEquals("not found; endpoints: POST /run, GET /state, GET /screens, GET /snapshot\n", missing.body)
        assertEquals("screens\n", router.handle(BridgeRequest("GET", "/screens")).body)
        assertEquals(
            "> (snapshot)\n  screen=items items=3 loading=false calls=items.fetch\n",
            router.handle(BridgeRequest("GET", "/snapshot")).body,
        )
    }

    @Test
    fun httpParsing() {
        val request = "POST /run?format=json HTTP/1.1\r\nHost: x\r\nContent-Length: 6\r\n\r\nsubmit"
        assertEquals(
            HttpParser.Result.Request(BridgeRequest("POST", "/run", mapOf("format" to "json"), "submit")),
            HttpParser.parse(request.toByteArray()),
        )
        assertEquals(HttpParser.Result.Incomplete, HttpParser.parse("POST /run HTTP/1.1\r\nContent-Length: 6\r\n\r\nsub".toByteArray()))
        assertEquals(HttpParser.Result.Incomplete, HttpParser.parse("GET /state HTTP/1.1\r\n".toByteArray()))
        assertEquals(HttpParser.Result.Malformed, HttpParser.parse("GARBAGE\r\n\r\n".toByteArray()))
        assertEquals(HttpParser.Result.Malformed, HttpParser.parse("GET / HTTP/1.1\r\nContent-Length: -1\r\n\r\n".toByteArray()))
        assertEquals(HttpParser.Result.Malformed, HttpParser.parse("GET / HTTP/1.1\r\nNoColon\r\n\r\n".toByteArray()))
        assertEquals(HttpParser.Result.Malformed, HttpParser.parse(ByteArray(64 * 1024 + 1) { 'a'.code.toByte() }))
        val response = String(HttpParser.serialize(BridgeResponse(200, "ok", exitCode = 1)), Charsets.UTF_8)
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(response.contains("X-Appctl-Exit: 1\r\n"))
        assertTrue(response.endsWith("\r\n\r\nok"))
    }
}

/**
 * What this guards: the in-app bridge, over a real loopback socket, against a live store of the example app on a
 * thread of its own (the main thread's stand-in). The central claim is the first test's: the same script produces
 * the same steps through the bridge as it does headlessly.
 */
class BridgeServerTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private var server: BridgeServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop()
        executor.shutdownNow()
    }

    /** A live app with zero latency whose runner synthesizes appearance (there are no views in a test). */
    private fun startBridge(): Int = runBlocking {
        withContext(dispatcher) {
            val app = TinyAppConfig.live(MockLatency.ZERO, dispatcher)
            val runner = app.makeRunner(synthesizesAppearance = true)
            runner.launch()
            val router = BridgeRouter(runner) { ScreensRenderer.render(TinyAppConfig.screens, TinyAppConfig.docsText.mockExample) }
            val server = BridgeServer(dispatcher) { router.handle(it) }
            this@BridgeServerTest.server = server
            server.start(0)
        }
    }

    private data class Answer(val status: Int, val body: String, val exit: String?)

    private fun request(method: String, path: String, port: Int, body: String? = null): Answer {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val text = (if (status >= 400) connection.errorStream else connection.inputStream).use { String(it.readBytes()) }
        return Answer(status, text, connection.getHeaderField("X-Appctl-Exit"))
    }

    /**
     * The script must not start anything that waits on the clock: the headless run is on virtual time and the live
     * run on real time, so a countdown would read the same only until its first tick. `save` is covered by
     * [poppingTheScreenCancelsTheCountdown] instead, which asserts the effect rather than the bytes.
     */
    @Test
    fun runReturnsTheSameStepsAsTheHeadlessRunner() {
        val script = "open 2; back"
        val headless = runBlocking {
            val runner = TinyAppConfig.headless().makeRunner()
            runner.launch()
            StepFormatter.text(runner.run(script).steps) + "\n"
        }
        val response = request("POST", "/run", startBridge(), script)
        assertEquals(200, response.status)
        assertEquals("0", response.exit)
        assertEquals(headless, response.body)
    }

    @Test
    fun poppingTheScreenCancelsTheCountdown() {
        val port = startBridge()
        val saved = request("POST", "/run", port, "open 2; save")
        assertEquals("0", saved.exit)
        assertTrue("saved=true" in saved.body && "pending=1" in saved.body, saved.body)
        val popped = request("POST", "/run", port, "back")
        assertEquals("0", popped.exit)
        assertTrue("screen=items" in popped.body)
        assertFalse("pending=" in popped.body, "the countdown outlived the screen: ${popped.body}")
    }

    @Test
    fun exitCodesTravelInAHeader() {
        val port = startBridge()
        assertEquals("0", request("POST", "/run", port, "expect screen=items").exit)
        assertEquals("1", request("POST", "/run", port, "expect screen=nope").exit)
        assertEquals("2", request("POST", "/run", port, "expect \"open").exit)
        val advance = request("POST", "/run", port, "advance 1s")
        assertEquals("2", advance.exit)
        assertTrue("FAIL advance is only available headlessly, not in the running app" in advance.body, advance.body)
        // `advance`'s duration is checked first, so a malformed one is the same usage error in both modes.
        assertTrue("advance needs a duration" in request("POST", "/run", port, "advance soon").body)
    }

    @Test
    fun otherEndpoints() {
        val port = startBridge()
        assertTrue("\"screen\" : \"items\"" in request("POST", "/run?format=json", port, "expect screen=items").body)
        assertTrue(request("GET", "/state", port).body.startsWith("State(\n"))
        assertTrue("items/<id>" in request("GET", "/screens", port).body)
        assertTrue("screen=items" in request("GET", "/snapshot", port).body)
        assertEquals(404, request("GET", "/nope", port).status)
        assertEquals(405, request("GET", "/run", port).status)
    }

    @Test
    fun malformedAndIncompleteRequests() {
        val port = startBridge()
        fun raw(text: String, close: Boolean): String = Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(text.toByteArray())
            if (close) socket.shutdownOutput()
            String(socket.getInputStream().readBytes())
        }
        val bad = raw("GARBAGE\r\n\r\n", close = false)
        assertTrue(bad.startsWith("HTTP/1.1 400 Bad Request\r\n") && bad.endsWith("\r\n\r\nbad request\n"), bad)
        val incomplete = raw("POST /run HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc", close = true)
        assertTrue(incomplete.contains("X-Appctl-Exit: 2\r\n") && incomplete.endsWith("incomplete request\n"), incomplete)
    }

    @Test
    fun aSeedRunsAfterASettledLaunch() = runBlocking(dispatcher) {
        val applied = AgentLaunchSession.applySeed("open 2", TinyAppConfig.live(MockLatency.ZERO, dispatcher).makeRunner(true))
        assertEquals(RunStatus.OK, applied.status)
        assertTrue(applied.log.startsWith("AgentCtlBridge: seed applied\n> (launch)"), applied.log)
        assertTrue("> open 2\n  screen=items/2" in applied.log)
        val unparsed = AgentLaunchSession.applySeed("open \"2", TinyAppConfig.live(MockLatency.ZERO, dispatcher).makeRunner(true))
        assertEquals(RunStatus.USAGE, unparsed.status)
        assertTrue(unparsed.log.endsWith("error: parse error: line 1, column 6: unterminated quote"), unparsed.log)
    }

    /** The whole launch path: seed first, then the server, so its first answer shows the seeded state. */
    @Test
    fun aLaunchSessionAppliesItsSeedBeforeListening() = runBlocking(dispatcher) {
        val logs = mutableListOf<String>()
        val session = AgentLaunchSession(TinyAppConfig.appCtl, AgentLaunchOptions(port = 0, seed = "open 2", latency = MockLatency.ZERO), dispatcher) {
            logs.add(it)
        }
        val port = session.start()!!
        try {
            assertTrue(logs.first().startsWith("AgentCtlBridge: seed applied"), logs.toString())
            assertEquals("AgentCtlBridge: listening on 127.0.0.1:$port", logs.last())
            val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { request("GET", "/snapshot", port) }
            assertTrue("screen=items/2" in snapshot.body, snapshot.body)
        } finally {
            session.stop()
        }
    }

    @Test
    fun launchArguments() {
        val options = AgentLaunchOptions.parse(
            listOf("TinyApp", "-agent-port", "9000", "-appctl-seed", "open 2; save", "-mock-latency", "0", "-clear-session"),
        )
        assertEquals(AgentLaunchOptions(9000, "open 2; save", MockLatency.ZERO, true), options)
        assertEquals(AgentLaunchOptions.parse(listOf("TinyApp")), AgentLaunchOptions.parse(emptyList()))
        assertEquals(BridgeDefaults.PORT, AgentLaunchOptions.parse(emptyList()).port)
        // A value that is not a port is ignored.
        assertEquals(BridgeDefaults.PORT, AgentLaunchOptions.parse(listOf("App", "-agent-port", "70000")).port)
        assertEquals(0, AgentLaunchOptions.parse(listOf("App", "-agent-port", "0")).port)
    }
}
