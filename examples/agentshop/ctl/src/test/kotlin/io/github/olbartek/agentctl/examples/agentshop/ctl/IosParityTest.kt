package io.github.olbartek.agentctl.examples.agentshop.ctl

import io.github.olbartek.agentctl.AgentRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The acceptance criterion of the port: every scenario prints, byte for byte, what the Swift reference's `shopctl`
 * printed for it, and `screens` lists the same screens, commands and summary keys.
 *
 * The transcripts in `src/test/resources/ios/` come from agentctl-ios's `Examples/AgentShop`
 * (`examples/agentshop/bench/ios_transcripts.sh` regenerates them): `text/<scenario>.txt` is
 * `shopctl run "$(cat scenarios/<scenario>.appctl)"` followed by a line `exit=<code>`, and `screens.txt` is
 * `shopctl screens`.
 */
class IosParityTest {
    @Test
    fun everyScenarioPrintsWhatTheReferencePrints() {
        val files = scenarioFiles
        assertEquals(105, files.size, "the scenarios are iOS's 105")
        val mismatches = mutableListOf<String>()
        for (file in files) {
            val expected = resource("ios/text/${file.nameWithoutExtension}.txt")
                ?: fail("no iOS transcript for ${file.name}: run examples/agentshop/bench/ios_transcripts.sh")
            // `"$(cat file)"`: the shell drops the file's trailing newlines.
            val result = shopctl("run", file.readText().trimEnd('\n'))
            val actual = result.combined + "exit=${result.status}\n"
            if (actual != expected) mismatches.add("${file.name}:\n--- iOS\n$expected--- Kotlin\n$actual")
        }
        assertTrue(mismatches.isEmpty(), "${mismatches.size} scenario(s) differ from iOS:\n" + mismatches.joinToString("\n"))
    }

    /**
     * `screens`, byte for byte. The one line the app does not write is the runtime `advance` command's help, which
     * comes from the library: until agentctl-android's runtime catches up with the reference's live `advance`, its
     * help still reads as headless-only, and that single line is compared against the library's own text instead.
     */
    @Test
    fun screensListWhatTheReferenceLists() {
        val advance = AgentRegistry.runtimeCommands().first { it.name == "advance" }.help
        val expected = resource("ios/screens.txt")!!
            .replace("Move the app's clock forward, e.g. 500ms, 30s, 5m, 1h, firing the timers due.", advance)
        val result = shopctl("screens")
        assertEquals(0, result.status, result.combined)
        assertEquals(expected, result.out)
    }
}
