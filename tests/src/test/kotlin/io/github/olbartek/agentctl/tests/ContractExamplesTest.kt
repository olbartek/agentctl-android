package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.runtime.AgentLaunchOptions
import io.github.olbartek.agentctl.runtime.BridgeRequest
import io.github.olbartek.agentctl.runtime.BridgeResponse
import io.github.olbartek.agentctl.runtime.BridgeRouter
import io.github.olbartek.agentctl.runtime.BridgeServer
import io.github.olbartek.agentctl.runtime.HttpParser
import java.io.File
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

/**
 * What this guards: that this port is a port. Every example in CONTRACT.md is the Swift reference's real output;
 * each one is run here against the Kotlin TinyApp and must print the same bytes, with the same exit code.
 */
class ContractExamplesTest {
    private val contract = File(repositoryRoot, "CONTRACT.md").readText()

    private data class Example(val command: String, val args: List<String>, val expected: String, val exit: Int?)

    /**
     * The fenced blocks — at any indentation, since some sit inside list items — that start with
     * `$ swift run tinyctl run|test`, with their output.
     */
    private fun examples(): List<Example> {
        val lines = contract.split("\n")
        val examples = mutableListOf<Example>()
        var index = 0
        while (index < lines.size - 1) {
            val fence = lines[index]
            val indent = fence.length - fence.trimStart().length
            if (fence.trim() != "```" || !lines[index + 1].trim().startsWith("\$ swift run tinyctl ")) {
                index += 1
                continue
            }
            val block = mutableListOf<String>()
            index += 1
            while (lines[index].trim() != "```") block.add(lines[index++].drop(indent))
            index += 1
            var command = block[0].removePrefix("\$ ").removeSuffix("; echo \"exit=\$?\"")
            var output = block.drop(1)
            var exit: Int? = null
            if (output.lastOrNull()?.startsWith("exit=") == true) {
                exit = output.last().removePrefix("exit=").toInt()
                output = output.dropLast(1)
            }
            val args = shellWords(command).drop(3)
            if (args.firstOrNull() in setOf("run", "test")) examples.add(Example(command, args, output.joinToString("\n") + "\n", exit))
        }
        return examples
    }

    @Test
    fun everyRunAndTestExampleIsReproducedByteForByte() {
        val examples = examples()
        assertEquals(11, examples.size, "the examples found in CONTRACT.md: ${examples.map { it.command }}")
        for (example in examples) {
            val result = tinyctl(*example.args.toTypedArray())
            assertEquals(example.expected, result.combined, example.command)
            example.exit?.let { assertEquals(it, result.status, "exit code of ${example.command}") }
        }
    }

    /** CONTRACT.md §8.4: TinyApp answering `POST /run` with `open 2`, and a script that does not parse, as JSON. */
    @Test
    fun theBridgeExamplesAreReproducedByteForByte() = runBlocking {
        val blocks = Regex("```\n(HTTP/1\\.1 .*?)```", RegexOption.DOT_MATCHES_ALL).findAll(contract).map { it.groupValues[1] }.toList()
        assertEquals(2, blocks.size)
        val requests = listOf(
            BridgeRequest("POST", "/run", body = "open 2"),
            BridgeRequest("POST", "/run", mapOf("format" to "json"), "expect \"open"),
        )
        for ((block, request) in blocks.zip(requests)) {
            val runner = TinyAppConfig.headless().makeRunner()
            runner.launch()
            val response = BridgeRouter(runner) { "" }.handle(request)
            // The documentation shows the head's CRLFs as newlines. The text body ends in a newline of its own; the
            // JSON body does not, and the newline after it closes the fence.
            val separator = block.indexOf("\n\n")
            val head = block.substring(0, separator + 2).replace("\n", "\r\n")
            val body = block.substring(separator + 2).let { if (request.query.isEmpty()) it else it.removeSuffix("\n") }
            val expected = head + body
            // §8.4's examples come from the iOS build of an app whose id is com.example.tinyapp.
            assertEquals(expected, String(HttpParser.serialize(response, "com.example.tinyapp", "ios"), Charsets.UTF_8))
        }
    }

    /**
     * CONTRACT.md §8.4: the request example, sent as written (its newlines as CRLFs) to a bridge expecting the
     * example's token, is routed whole: past every check of §8.1, with its body. Its token is a launch token (§8.2),
     * and the same request with another one is refused.
     */
    @Test
    fun theBridgeRequestExampleIsAccepted() {
        val block = Regex("```\n(POST /run HTTP/1\\.1\n.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(contract)
            .map { it.groupValues[1] }.single()
        val separator = block.indexOf("\n\n")
        val token = Regex("X-Appctl-Token: (\\S+)").find(block)!!.groupValues[1]
        assertTrue(AgentLaunchOptions.isToken(token), token)
        val wire = block.substring(0, separator + 2).replace("\n", "\r\n") + block.substring(separator + 2).removeSuffix("\n")
        val executor = Executors.newSingleThreadExecutor()
        val received = AtomicReference<BridgeRequest>()
        val server = BridgeServer(executor.asCoroutineDispatcher(), "com.example.tinyapp", "ios", token) {
            received.set(it)
            BridgeResponse(200, "ok\n", exitCode = 0)
        }
        try {
            val port = server.start(0)
            fun send(wire: String) = Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().apply { write(wire.toByteArray()); flush() }
                String(socket.getInputStream().readBytes())
            }
            val other = token.dropLast(1) + if (token.last() == '0') '1' else '0'
            val refused = send(wire.replace(token, other))
            assertTrue(refused.startsWith("HTTP/1.1 401 Unauthorized\r\n"), refused)
            assertEquals(null, received.get())
            val answer = send(wire)
            assertTrue(answer.startsWith("HTTP/1.1 200 OK\r\n"), answer)
            assertEquals("open 2", received.get()?.body)
        } finally {
            server.stop()
            executor.shutdownNow()
        }
    }
}
