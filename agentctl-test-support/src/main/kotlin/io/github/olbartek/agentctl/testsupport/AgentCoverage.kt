package io.github.olbartek.agentctl.testsupport

import io.github.olbartek.agentctl.AgentRegistry
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.ScriptParser
import io.github.olbartek.agentctl.runtime.ScenarioResult
import io.github.olbartek.agentctl.runtime.ScenarioRunner
import io.github.olbartek.agentctl.runtime.ScriptRunner
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Thrown by [AgentCoverage]'s constructor when `scenarios` has no `*.appctl` files to check.
 *
 * Every guard on [AgentCoverage] answers "what's missing" by scanning the scenario files found at `scenarios`; an
 * empty result means "no problems found". That reading is only meaningful if there were scenario files to scan in
 * the first place. A misconfigured or renamed directory would otherwise make every guard return an empty list — a
 * guard that always passes without having examined anything. The constructor refuses instead, naming the exact
 * path it looked at. It does not search elsewhere: a wrong path is a bug to fix, not to work around.
 */
public class NoScenariosFound(public val scenarios: File) : Exception(
    "AgentCoverage: no *.appctl files found at ${scenarios.path} — check the `scenarios` argument passed to " +
        "AgentCoverage; every coverage guard would otherwise report success without checking anything.",
)

/**
 * The guards that keep an app's agent surface honest as screens are added. Construct one with every screen the
 * app documents (its container's `registry`) and the directory its `*.appctl` scenarios live in, then ask:
 *
 * - [unusedCommands]: command names no scenario sends.
 * - [undocumentedSummaryKeys]: summary keys a scenario run emits that no screen's `summaryKeys` lists.
 * - [unvisitedScreens]: documented screen paths no scenario run visits. A pattern segment written `<id>` matches any
 *   concrete segment, so a screen with a variable path needs one scenario, not one per instance.
 *
 * All three use the scenario files found when this value was constructed.
 *
 * They are deliberately shallow: they check that a command *appears* in some script, not that its result was
 * asserted. A thin suite that touches everything once passes them.
 *
 * @throws NoScenariosFound if `scenarios` contains no `*.appctl` files.
 */
public class AgentCoverage<S, A>(
    private val screens: List<ScreenDoc>,
    scenarios: File,
    private val make: () -> ScriptRunner<S, A>,
) {
    private val files: List<File> = ScenarioRunner.files(scenarios)

    init {
        if (files.isEmpty()) throw NoScenariosFound(scenarios)
    }

    /** The number of `*.appctl` files found; always greater than zero. */
    public val scenarioCount: Int get() = files.size

    /** Command names no scenario sends. */
    public fun unusedCommands(): List<String> {
        val used = files.flatMap { file -> ScriptParser.parse(file.readText()).map { it.name } }.toSet()
        return (AgentRegistry.allCommandNames(screens) - used).sorted()
    }

    /** Summary keys emitted during the scenarios that no screen documents. */
    public fun undocumentedSummaryKeys(): List<String> {
        val undocumented = sortedSetOf<String>()
        for (result in results()) {
            for (step in result.steps) {
                val doc = screens.firstOrNull { matches(it.path, step.screen) }
                if (doc == null) {
                    undocumented.add("${step.screen}: no documented screen")
                    continue
                }
                step.summary.filter { it.key !in doc.summaryKeys }.forEach { undocumented.add("${doc.path}: ${it.key}") }
            }
        }
        return undocumented.toList()
    }

    /** Documented screens no scenario visits. */
    public fun unvisitedScreens(): List<String> {
        val visited = results().flatMap { result -> result.steps.map { it.screen } }.toSet()
        return screens.map { it.path }.filter { pattern -> visited.none { matches(pattern, it) } }
    }

    private fun results(): List<ScenarioResult> = runBlocking { files.map { ScenarioRunner.run(it, make) } }

    public companion object {
        /** `items/<id>` matches `items/2`. */
        public fun matches(pattern: String, path: String): Boolean {
            val patternParts = pattern.split("/").filter { it.isNotEmpty() }
            val pathParts = path.split("/").filter { it.isNotEmpty() }
            if (patternParts.size != pathParts.size) return false
            return patternParts.zip(pathParts).all { (expected, actual) -> expected.startsWith("<") || expected == actual }
        }
    }
}
