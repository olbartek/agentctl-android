package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.examples.tinyapp.TinyRoot
import io.github.olbartek.agentctl.examples.tinyapp.TinyRootAgent
import io.github.olbartek.agentctl.testsupport.AgentCoverage
import io.github.olbartek.agentctl.testsupport.NoScenariosFound
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What this guards: the coverage guards, proved against the example app, the way a host wires them in. */
class CoverageTest {
    private fun coverage(): AgentCoverage<TinyRoot.State, TinyRoot.Action> =
        AgentCoverage(TinyRootAgent.registry, scenariosDirectory) { TinyAppConfig.headless().makeRunner() }

    @Test
    fun everyCommandIsUsedByAScenario() {
        assertEquals(emptyList(), coverage().unusedCommands())
        assertEquals(3, coverage().scenarioCount)
    }

    @Test
    fun everyEmittedSummaryKeyIsDocumented() {
        assertEquals(emptyList(), coverage().undocumentedSummaryKeys())
    }

    @Test
    fun everyScreenIsVisited() {
        assertEquals(emptyList(), coverage().unvisitedScreens())
    }

    @Test
    fun aDirectoryWithoutScenariosIsRefused() {
        val empty = File(repositoryRoot, "examples/tinyapp/src")
        val error = assertFailsWith<NoScenariosFound> { AgentCoverage(TinyRootAgent.registry, empty) { TinyAppConfig.headless().makeRunner() } }
        assertTrue(error.message!!.contains(empty.path))
    }

    @Test
    fun patternsMatchVariableSegments() {
        assertTrue(AgentCoverage.matches("items/<id>", "items/2"))
        assertFalse(AgentCoverage.matches("items/<id>", "items"))
        assertFalse(AgentCoverage.matches("items", "items/2"))
        assertTrue(AgentCoverage.matches("items", "items"))
    }
}
