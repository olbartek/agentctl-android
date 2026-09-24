package io.github.olbartek.agentctl.cli

import io.github.olbartek.agentctl.BridgeDefaults
import io.github.olbartek.agentctl.runtime.ScenarioRunner
import java.io.File
import java.util.Locale
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

/**
 * The CLI's `check`: the verification ladder — build, tests, scenarios and the generated docs, then the two UI
 * stages — cheapest first, one line per stage, stopping at the first failure.
 */
internal class Ladder(private val cli: Cli<*, *>, private val root: File, private val ui: Boolean, private val device: String?) {
    private val config = cli.config
    private val layout = Layout(root, config.outputPath)
    private val io = cli.io

    fun run(): Int {
        layout.logs.mkdirs()
        if (!build()) return 1
        if (!test()) return 1
        if (!scenarios()) return 1
        if (!docs()) return 1
        if (ui) {
            if (!snapshots()) return 1
            if (!app()) return 1
        }
        return 0
    }

    /** L0: the config's build tasks. */
    private fun build(): Boolean {
        val start = TimeSource.Monotonic.markNow()
        val tasks = config.gradle.build
        val log = File(layout.logs, "L0-build.log")
        val status = Shell.run(Gradle.command(root, tasks), root, log)
        if (status != 0) {
            report("L0 build", false, "${tasks.joinToString(" ")} failed", start)
            printFailure(log)
            return false
        }
        report("L0 build", true, count(tasks.size, "task"), start)
        return true
    }

    /** L1: the config's test tasks, with the number of tests Gradle's JUnit reports counted. */
    private fun test(): Boolean {
        val start = TimeSource.Monotonic.markNow()
        val tasks = config.gradle.test
        val log = File(layout.logs, "L1-test.log")
        val status = Shell.run(Gradle.command(root, tasks), root, log)
        if (status != 0) {
            report("L1 test", false, "${tasks.joinToString(" ")} failed", start)
            printFailure(log)
            return false
        }
        report("L1 test", true, "${count(tasks.size, "task")}, ${count(TestReports.count(root), "test")}", start)
        return true
    }

    /** L2: every scenario, in-process. Internal rather than private so a test can run this rung on its own. */
    fun scenarios(): Boolean = runBlocking {
        val start = TimeSource.Monotonic.markNow()
        val directory = File(root, config.scenariosPath)
        val files = ScenarioRunner.files(directory)
        if (files.isEmpty()) {
            report("L2 scenarios", false, "no scenario files", start)
            io.print("  ${Message.noScenarios(directory)}")
            return@runBlocking false
        }
        val results = config.runScenarios(files)
        val failures = results.filter { !it.passed }
        report("L2 scenarios", failures.isEmpty(), "${results.size - failures.size}/${results.size} scenarios", start)
        failures.forEach { failure -> io.print(failure.report.split("\n").joinToString("\n") { "  $it" }) }
        failures.isEmpty()
    }

    private fun docs(): Boolean {
        val start = TimeSource.Monotonic.markNow()
        val file = File(root, config.docsPath)
        val upToDate = file.isFile && file.readText() == cli.docsMarkdown
        report("docs", upToDate, if (upToDate) "up to date" else Message.staleDocsDetail(config), start)
        return upToDate
    }

    /** L3: the screenshot tests against their references. */
    private fun snapshots(): Boolean {
        val start = TimeSource.Monotonic.markNow()
        val result = Snapshots(cli, root).run(record = false)
        report("L3 snapshots", result.ok, result.summary, start)
        result.details.forEach { io.print("  $it") }
        return result.ok
    }

    /** L4: the real app. Launch it seeded on a device, run a scenario through its agent bridge, check it, screenshot. */
    private fun app(): Boolean {
        val start = TimeSource.Monotonic.markNow()
        val port = BridgeDefaults.PORT
        val check = config.appCheck
        val scenarios = File(root, config.scenariosPath)
        val scenario = check.scenario ?: ScenarioRunner.files(scenarios).firstOrNull()?.nameWithoutExtension
        if (scenario == null) {
            report("L4 app", false, "no scenario to run", start)
            return false
        }
        return try {
            val launcher = AppLauncher(cli, root)
            // Zero mock latency: the bridge answers as soon as it listens, while the first screen's own appearance
            // may still be loading, and a scenario's first `expect` reads the state as it is.
            val launched = launcher.launch(check.seed, device, latency = 0, clearSession = true, build = true, port = port)
            val script = File(scenarios, "$scenario.appctl").readText()
            val bridge = BridgeClient(port)
            val run = bridge.send("POST", "/run", script)
            if (run.exitCode != 0) {
                report("L4 app", false, "$scenario failed in the app", start)
                io.print(run.body.trimEnd('\n').split("\n").joinToString("\n") { "  $it" })
                return false
            }
            check.expectScreen?.let { expected ->
                val snapshot = bridge.send("GET", "/snapshot")
                if ("screen=$expected" !in snapshot.body) {
                    report("L4 app", false, "unexpected final screen", start)
                    io.print("  ${snapshot.body.trimEnd('\n')}")
                    return false
                }
            }
            val screenshot = File(layout.screenshots, "check-ui-${System.currentTimeMillis() / 1000}.png")
            launcher.screenshot(launched.device, screenshot)
            report("L4 app", true, "$scenario via the agent bridge", start)
            io.print("  ${launched.device.label}, screenshot: ${screenshot.path}")
            true
        } catch (error: Exception) {
            report("L4 app", false, "error", start)
            io.print("  ${error.message ?: error}")
            false
        }
    }

    private fun report(stage: String, ok: Boolean, detail: String, start: TimeSource.Monotonic.ValueTimeMark) {
        io.print(row(stage, ok, detail, start.elapsedNow().inWholeMilliseconds / 1000.0))
    }

    private fun printFailure(log: File) {
        val text = if (log.isFile) log.readText() else ""
        text.split("\n")
            .filter { "error:" in it || "e: " in it || "FAILED" in it || "What went wrong" in it }
            .take(20)
            .forEach { io.print("  $it") }
        io.print("  full log: ${log.path}")
    }

    companion object {
        /** "1 task", "6 tasks". */
        fun count(number: Int, noun: String): String = "$number $noun${if (number == 1) "" else "s"}"

        /** One line of the ladder's report. Columns are padded to line up but never truncated. */
        fun row(stage: String, ok: Boolean, detail: String, seconds: Double): String =
            listOf(stage.padEnd(13), (if (ok) "ok" else "FAIL").padEnd(5), detail.padEnd(28), String.format(Locale.ROOT, "%.1fs", seconds))
                .joinToString(" ")
    }
}

/**
 * Counts the tests behind the test tasks, from the JUnit XML reports Gradle keeps under `build/test-results`. A task
 * that was up to date did not run again, and its reports are still the current ones, so every report counts.
 */
internal object TestReports {
    private val testsAttribute = Regex("""<testsuite\b[^>]*\btests="(\d+)"""")

    fun count(root: File): Int = root.walkTopDown()
        .onEnter { it.name != ".git" && it.name != ".gradle" && it.name != "node_modules" }
        .filter { it.isFile && it.extension == "xml" && it.parentFile?.parentFile?.name == "test-results" }
        .sumOf { file -> testsAttribute.find(file.readText())?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
}
