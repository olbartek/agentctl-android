package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.ScriptLine
import io.github.olbartek.agentctl.StepFormatter
import io.github.olbartek.agentctl.StepRecord
import java.io.File
import java.io.IOException
import kotlin.time.TimeSource

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
    public suspend fun <S, A> run(name: String, source: String, make: () -> ScriptRunner<S, A>): ScenarioResult {
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

    public suspend fun <S, A> run(file: File, make: () -> ScriptRunner<S, A>): ScenarioResult {
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
