package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.ScriptLine
import io.github.olbartek.agentctl.StepFormatter
import io.github.olbartek.agentctl.StepRecord
import java.io.File
import java.io.IOException
import kotlin.time.TimeSource

/**
 * One scenario's run: its steps (the `(launch)` step first), its exit status, the failing line and message if any, and
 * how long it took in real time.
 */
public data class ScenarioResult(
    val name: String,
    val steps: List<StepRecord>,
    val status: RunStatus,
    val failedLine: ScriptLine?,
    val message: String?,
    val milliseconds: Long,
) {
    val passed: Boolean get() = status == RunStatus.OK

    /** `PASS name (N steps, X ms)` or `FAIL name:line` followed by the failing step. */
    val report: String
        get() {
            if (passed) return "PASS $name (${steps.size} steps, $milliseconds ms)"
            val lines = mutableListOf("FAIL $name${failedLine?.let { ":${it.line}" } ?: ""}")
            message?.let { lines.add("  $it") }
            steps.lastOrNull()?.let { failing -> StepFormatter.text(failing).split("\n").mapTo(lines) { "  $it" } }
            return lines.joinToString("\n")
        }
}

/** Runs `scenarios/<name>.appctl` files, each against a fresh [ScriptRunner] from `make`. */
public object ScenarioRunner {
    /**
     * Runs [source] as the scenario [name] on a fresh runner from [make]: the launch, then the script. A launch that
     * did not settle fails the scenario before its first line.
     */
    public suspend fun <A> run(name: String, source: String, make: () -> ScriptRunner<A>): ScenarioResult {
        val start = TimeSource.Monotonic.markNow()
        val runner = make()
        val launch = runner.launch()
        // An app that never settled at launch fails the scenario before its first line runs.
        if (launch.status != RunStatus.OK) {
            return ScenarioResult(name, listOf(launch.step), launch.status, null, null, start.elapsedNow().inWholeMilliseconds)
        }
        val result = runner.run(source)
        return ScenarioResult(
            name = name,
            steps = listOf(launch.step) + result.steps,
            status = result.status,
            failedLine = result.failedLine,
            message = result.message,
            milliseconds = start.elapsedNow().inWholeMilliseconds,
        )
    }

    /**
     * Runs [file] as the scenario named after it. A file that cannot be read fails with exit code 3, an environment
     * problem rather than the script's.
     */
    public suspend fun <A> run(file: File, make: () -> ScriptRunner<A>): ScenarioResult {
        val name = file.nameWithoutExtension
        // A missing or unreadable file is an environment problem, not a script's: exit code 3 (CONTRACT.md §5).
        val source = try {
            file.readText()
        } catch (_: IOException) {
            return ScenarioResult(name, emptyList(), RunStatus.INTERNAL_ERROR, null, "cannot read ${file.path}", 0)
        }
        return run(name, source, make)
    }

    /** Every `*.appctl` file in `directory`, sorted by name. */
    public fun files(directory: File): List<File> =
        directory.listFiles { file -> file.isFile && file.extension == "appctl" }?.sortedBy { it.name } ?: emptyList()
}
