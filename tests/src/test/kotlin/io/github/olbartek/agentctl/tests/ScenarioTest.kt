package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.runtime.ScenarioRunner
import io.github.olbartek.agentctl.testsupport.AgentScenarioChecks
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What this guards: the example's scenarios all pass under the unit tests, not only under `tinyctl test`, and the
 * promises the toolkit makes about them hold — byte-identical output across fresh runs (CONTRACT.md §6), a
 * `--session` run that resumes where it left off, a command reference that is current. The checks are
 * [AgentScenarioChecks], the same ones a host app runs against its own config.
 */
class ScenarioTest {
    private val checks = AgentScenarioChecks(TinyAppConfig.appCtl)

    private fun assertNone(problems: List<String>) = assertTrue(problems.isEmpty(), problems.joinToString("\n"))

    /** `tinyctl test` and the unit tests run the same files: both find the root by the config's marker. */
    @Test
    fun theConfigPointsAtTheseScenarios() {
        assertEquals(repositoryRoot.canonicalFile, checks.root.canonicalFile)
        assertEquals(listOf("browse", "refresh-error", "save-cooldown"), checks.files.map { it.nameWithoutExtension })
        assertEquals(ScenarioRunner.files(scenariosDirectory), checks.files)
    }

    @Test
    fun everyScenarioPasses() = assertNone(checks.allPass())

    @Test
    fun everyScenarioEndsWithAnExpect() = assertNone(checks.endWithExpect())

    @Test
    fun scenariosAreDeterministic() = assertNone(checks.deterministic(runs = 10))

    @Test
    fun aSessionReplayMatchesASingleRun() = assertNone(checks.sessionReplayMatches(parts = 3))

    /** The example's README is its scenario index; TinyApp keeps it beside the app rather than in `scenarios/`. */
    @Test
    fun theReadmeListsEveryScenario() = assertNone(checks.readmeListsAll(File(checks.root, "examples/tinyapp/README.md")))

    /** The same guard a host gets from `docs --check`, and that command's own answer; `./tinyctl docs` rewrites the file. */
    @Test
    fun docsAreUpToDate() {
        assertNone(checks.docsCurrent())
        val result = tinyctl("docs", "--check")
        assertEquals("examples/tinyapp/agent-commands.md is up to date.\n", result.out, result.combined)
        assertEquals(0, result.status)
    }

    @Test
    fun stepCountsMatchTheReference() = runBlocking {
        // The reference's README: browse (10 steps), refresh-error (7 steps), save-cooldown (13 steps).
        val counts = checks.files.associate { file -> file.nameWithoutExtension to ScenarioRunner.run(file) { TinyAppConfig.headless().makeRunner() }.steps.size }
        assertEquals(mapOf("browse" to 10, "refresh-error" to 7, "save-cooldown" to 13), counts)
    }
}
