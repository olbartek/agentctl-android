package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.runtime.BridgeRequest
import io.github.olbartek.agentctl.runtime.BridgeRouter
import io.github.olbartek.agentctl.runtime.HttpParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
        assertEquals(8, examples.size, "the examples found in CONTRACT.md: ${examples.map { it.command }}")
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
            assertEquals(expected, String(HttpParser.serialize(response), Charsets.UTF_8))
        }
    }
}
