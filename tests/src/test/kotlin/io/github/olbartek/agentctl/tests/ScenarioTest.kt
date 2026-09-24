package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.StepFormatter
import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.runtime.ScenarioRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What this guards: the example's scenarios pass, run after run with the same bytes (CONTRACT.md §6), and the
 * committed command reference is what the registry renders.
 */
class ScenarioTest {
    private val files: List<File> = ScenarioRunner.files(scenariosDirectory)

    @Test
    fun theExampleHasScenarios() {
        assertEquals(listOf("browse", "refresh-error", "save-cooldown"), files.map { it.nameWithoutExtension })
    }

    @Test
    fun everyScenarioPasses() = runBlocking {
        for (file in files) {
            val result = ScenarioRunner.run(file) { TinyAppConfig.headless().makeRunner() }
            assertTrue(result.passed, result.report)
        }
    }

    /** Ten runs of every scenario, each from a fresh app, print identical steps. */
    @Test
    fun scenariosAreDeterministic() = runBlocking {
        for (file in files) {
            val outputs = (1..10).map {
                StepFormatter.text(ScenarioRunner.run(file) { TinyAppConfig.headless().makeRunner() }.steps)
            }
            assertEquals(1, outputs.toSet().size, "${file.name} printed different steps across runs")
        }
    }

    @Test
    fun stepCountsMatchTheReference() = runBlocking {
        // The reference's README: browse (10 steps), refresh-error (7 steps), save-cooldown (13 steps).
        val counts = files.associate { file -> file.nameWithoutExtension to ScenarioRunner.run(file) { TinyAppConfig.headless().makeRunner() }.steps.size }
        assertEquals(mapOf("browse" to 10, "refresh-error" to 7, "save-cooldown" to 13), counts)
    }

    @Test
    fun theCommittedCommandReferenceIsCurrent() {
        val result = tinyctl("docs", "--check")
        assertEquals("examples/tinyapp/agent-commands.md is up to date.\n", result.out, result.combined)
        assertEquals(0, result.status)
    }
}
