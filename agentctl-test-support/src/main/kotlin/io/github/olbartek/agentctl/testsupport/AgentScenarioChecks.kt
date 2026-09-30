package io.github.olbartek.agentctl.testsupport

import io.github.olbartek.agentctl.ArgumentText
import io.github.olbartek.agentctl.ScriptError
import io.github.olbartek.agentctl.ScriptLine
import io.github.olbartek.agentctl.ScriptParser
import io.github.olbartek.agentctl.StepFormatter
import io.github.olbartek.agentctl.StepRecord
import io.github.olbartek.agentctl.runtime.AppCtlConfig
import io.github.olbartek.agentctl.runtime.RepoRoot
import io.github.olbartek.agentctl.runtime.RunStatus
import io.github.olbartek.agentctl.runtime.ScenarioResult
import io.github.olbartek.agentctl.runtime.ScenarioRunner
import io.github.olbartek.agentctl.runtime.ScriptRunner
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * Thrown by [AgentScenarioChecks]'s constructor when no directory at or above [start] holds the config's root
 * marker, so there is nowhere to resolve `scenariosPath` and `docsPath` from.
 */
public class RepoRootNotFound(
    /** The file the search looked for: the config's `rootMarker`. */
    public val marker: String,
    /** The directory the search started from. */
    public val start: File,
) : Exception(
    "no $marker at or above ${start.path} — the config's rootMarker must exist at the repo root; or pass `root` to " +
        "AgentScenarioChecks",
)

/**
 * The scenario guards every host app would otherwise write for itself: that its unit tests alone catch what
 * `appctl test` and `appctl docs --check` catch, and that the promises the agent docs make — the same script prints
 * the same output, `--session` resumes where it left off, a step never shows a secret — hold for **this app's**
 * wiring. The library proving them for its example app proves nothing about another app, because determinism and
 * privacy are properties of the whole config: its clocks, its seeds, its mocks.
 *
 * Construct one from the app's [AppCtlConfig]; it finds the repo root the way the CLI does (by the config's root
 * marker), walking up from the test's working directory (a Gradle test task's is its module's directory), then reads
 * the `*.appctl` files at `scenariosPath` once. Every check returns the problems it found as readable lines, empty
 * when there are none, so a test is one line:
 *
 * ```kotlin
 * val problems = AgentScenarioChecks(MyAppConfig.appCtl).deterministic(runs = 10)
 * assertTrue(problems.isEmpty(), problems.joinToString("\n"))
 * ```
 *
 * Headless runs are deterministic by construction (each runs on its own virtual-time dispatcher), so the checks need
 * no process-wide switch and may run beside other tests.
 *
 * Like [AgentCoverage], the constructor throws [NoScenariosFound] when there are no scenario files, so no check can
 * pass having examined nothing.
 *
 * @param root the repo root; `null` finds it: the nearest directory at or above [start] that holds the config's root
 *   marker.
 * @param start where the search for the root starts: the working directory by default.
 * @throws RepoRootNotFound when [root] is `null` and no ancestor of [start] holds the marker.
 * @throws NoScenariosFound when the scenarios directory holds no `*.appctl` files.
 */
public class AgentScenarioChecks<S, A>(
    /** The app's config: its scenarios, its docs and the headless runner every check drives. */
    public val config: AppCtlConfig<S, A>,
    root: File? = null,
    start: File = File(System.getProperty("user.dir")),
) {
    /** The repo root `scenariosPath` and `docsPath` are resolved against. */
    public val root: File = root ?: RepoRoot.find(config.rootMarker, start) ?: throw RepoRootNotFound(config.rootMarker, start)

    /** The directory the scenario files were read from. */
    public val scenarios: File get() = File(root, config.scenariosPath)

    /** The `*.appctl` files at [scenarios], sorted by name, read once when this value was constructed. */
    public val files: List<File> = ScenarioRunner.files(File(this.root, config.scenariosPath))

    init {
        if (files.isEmpty()) throw NoScenariosFound(scenarios)
    }

    /**
     * Every scenario passes headlessly: what `appctl test` checks. One line per failing scenario, its `FAIL name:line`
     * report with the failing step.
     */
    public fun allPass(): List<String> = files.map(::run).filter { !it.passed }.map { it.report }

    /**
     * Every scenario's last command is an `expect`. A scenario that ends on a plain command asserts nothing about
     * where it ended up: its last step could report anything and it would still pass.
     */
    public fun endWithExpect(): List<String> = files.mapNotNull { file ->
        val lines = try {
            parse(file)
        } catch (error: Exception) {
            return@mapNotNull "${file.name}: cannot be read or parsed: ${describe(error)}"
        }
        val last = lines.lastOrNull() ?: return@mapNotNull "${file.name} has no commands"
        if (last.name == "expect") null else "${file.name} ends with `${last.text}` (line ${last.line}), not an expect"
    }

    /**
     * Every scenario prints byte-identical step output across [runs] fresh runs, each against its own runner.
     *
     * Ten runs by default, because what this catches — an unordered collection in a summary, a real clock leaking
     * into an effect, work on another dispatcher finishing in whatever order — is intermittent, and a single repeat
     * would usually agree with the first run by luck.
     */
    public fun deterministic(runs: Int = 10): List<String> {
        require(runs >= 2) { "deterministic(runs) compares runs with each other; it needs at least 2" }
        return files.mapNotNull { file ->
            val outputs = List(runs) { StepFormatter.text(run(file).steps) }
            val distinct = outputs.toSet().size
            val other = outputs.indexOfFirst { it != outputs[0] }
            if (distinct <= 1 || other < 0) return@mapNotNull null
            "${file.name} produced $distinct different outputs in $runs runs; " +
                firstDifference(outputs[0], outputs[other], "run 1" to "run ${other + 1}")
        }
    }

    /**
     * Splitting each scenario into [parts] consecutive runs, resumed through a session the way `run --session`
     * resumes one, prints the same steps and ends in the same state as running it in one go.
     *
     * This is the CLI's `--session` promise, replayed through the same library calls it makes: each part gets a
     * fresh runner that launches, replays the lines the earlier parts executed, then runs its own lines, and adds
     * those it executed to the session. As with `run --session`, no part prints the launch step, so the parts' steps
     * together are compared with the single run's steps after its launch; then the final state dumps (`appctl
     * state`) are compared. A scenario that already fails in one run is reported and not compared; one with a single
     * command has nothing to split and is skipped.
     *
     * The parts run in this one test process, where the CLI starts a process per `run --session`: state a host keeps
     * outside its store for the whole process (an in-memory singleton, a static cache) carries over between parts
     * here and not there, and can make this report a mismatch the CLI would not have.
     */
    public fun sessionReplayMatches(parts: Int = 3): List<String> {
        require(parts >= 2) { "sessionReplayMatches(parts) needs at least 2 parts to replay a session" }
        return files.mapNotNull { runBlocking { replayProblem(it, parts) } }
    }

    /**
     * The committed command reference at the config's `docsPath` is what the CLI's `docs` would write today: the same
     * rendering ([AppCtlConfig.docsMarkdown]) from the same config, so this fails exactly when `docs --check` would.
     *
     * @param root where `docsPath` is resolved; this value's [root] by default.
     */
    public fun docsCurrent(root: File? = null): List<String> {
        val path = config.docsPath
        val file = File(root ?: this.root, path)
        val regenerate = "Run ${config.help.invocation} docs."
        val existing = try {
            file.readText()
        } catch (_: IOException) {
            return listOf("$path does not exist at ${file.path}. $regenerate")
        }
        val generated = config.docsMarkdown
        if (existing == generated) return emptyList()
        return listOf("$path is stale. $regenerate " + firstDifference(existing, generated, "committed" to "generated"))
    }

    /**
     * A scenarios README lists every scenario file, and names none that does not exist: the index a person or an
     * agent reads to find a flow must not hide one or point at nothing.
     *
     * A file counts as listed when its name appears in backticks, optionally after a path: `` `login.appctl` `` or
     * `` `scenarios/login.appctl` ``. Backticks, so prose that mentions a file in passing is not a listing, and a glob
     * such as `` `*.appctl` `` matches nothing.
     *
     * @param readme the README to read; `README.md` in the scenarios directory by default.
     */
    public fun readmeListsAll(readme: File? = null): List<String> {
        val file = readme ?: File(scenarios, "README.md")
        val text = try {
            file.readText()
        } catch (_: IOException) {
            return listOf("cannot read ${file.path}: the scenarios README must list every scenario file")
        }
        val listed = scenarioNames(text)
        val onDisk = files.map { it.name }.toSet()
        val problems = mutableListOf<String>()
        val missing = (onDisk - listed).sorted()
        val unknown = (listed - onDisk).sorted()
        if (missing.isNotEmpty()) problems.add("${file.name} does not list: ${missing.joinToString(", ")}")
        if (unknown.isNotEmpty()) {
            problems.add("${file.name} names files that are not in ${config.scenariosPath}: ${unknown.joinToString(", ")}")
        }
        return problems
    }

    /**
     * No step any scenario prints shows a secret: neither one of [secrets] nor any value a scenario types into one of
     * [personalCommands], compared ignoring case (`KOWALSKA` leaks a name as much as `Kowalska`).
     *
     * A step is read as an agent reads it, without the echo of the command it ran (`> password hunter2` is what the
     * agent itself sent): its `screen=… key=value …` line and any message. The forbidden values stay in the app's own
     * test; only the scan lives here.
     *
     * A value is matched as a substring, so a very short one (`code 1`) matches inside unrelated values (`items=1`).
     * Keep the forbidden values long enough to be telling.
     *
     * @param secrets values no step may show, such as seeded personal data or a mock's one-time code.
     * @param personalCommands commands whose argument is personal or secret (`email`, `password`). Every argument a
     *   scenario file gives one of them, unquoted as the runner unquotes it, is forbidden too. Each must be typed by
     *   at least one scenario, or its values would be scanned for nowhere — reported as a problem.
     */
    public fun noStepShows(secrets: List<String>, personalCommands: Set<String> = emptySet()): List<String> {
        val problems = mutableListOf<String>()
        val typed = mutableListOf<String>()
        val used = mutableSetOf<String>()
        for (file in files) {
            try {
                for (line in parse(file)) {
                    if (line.name !in personalCommands) continue
                    used.add(line.name)
                    line.argument?.let { typed.add(ArgumentText.unquoted(it)) }
                }
            } catch (error: Exception) {
                problems.add("${file.name}: cannot be read or parsed: ${describe(error)}")
            }
        }
        for (command in (personalCommands - used).sorted()) {
            problems.add("no scenario types `$command`, so none of its values is scanned for")
        }
        val forbidden = (secrets + typed).filter { it.isNotEmpty() }.toSortedSet().toList()
        if (forbidden.isEmpty()) {
            return problems + "nothing to scan for: no secrets were given and no scenario types a personal command"
        }
        val leaks = mutableListOf<String>()
        for (file in files) {
            val result = run(file)
            if (!result.passed) {
                problems.add("${file.name} failed, so the steps after its failure were not scanned:\n${result.report}")
            }
            for (step in result.steps) {
                val text = summaryText(step)
                for (value in forbidden) {
                    if (text.contains(value, ignoreCase = true)) leaks.add("${file.name}: `$value` in `${step.command}`: $text")
                }
            }
        }
        problems.addAll(leaks.take(SHOWN_LEAKS))
        if (leaks.size > SHOWN_LEAKS) problems.add("… and ${leaks.size - SHOWN_LEAKS} more leaks")
        return problems
    }

    private fun run(file: File): ScenarioResult = runBlocking { ScenarioRunner.run(file) { config.makeRunner() } }

    private fun parse(file: File): List<ScriptLine> = ScriptParser.parse(file.readText())

    private fun describe(error: Exception): String = if (error is ScriptError) error.description else error.toString()

    /** [sessionReplayMatches] for one file: `null` when the split run matches the single one. */
    private suspend fun replayProblem(file: File, parts: Int): String? {
        val name = file.name
        val lines = try {
            parse(file)
        } catch (error: Exception) {
            return "$name: cannot be read or parsed: ${describe(error)}"
        }
        val single = config.makeRunner()
        if (single.launch().status != RunStatus.OK) return "$name: the app did not settle at launch"
        // One command cannot be split into a session; there is nothing to replay.
        if (lines.size < 2) return null
        val whole = single.run(lines)
        if (whole.status != RunStatus.OK) return "$name fails in one run, so its session replay was not compared"

        val session = mutableListOf<ScriptLine>()
        val steps = mutableListOf<StepRecord>()
        var last: ScriptRunner<S, A>? = null
        for ((index, part) in split(lines, parts).withIndex()) {
            val label = "$name, part ${index + 1} of ${minOf(parts, lines.size)}"
            val runner = config.makeRunner()
            if (runner.launch().status != RunStatus.OK) return "$label: the app did not settle at launch"
            // The session file holds each executed line's text, one per line; the CLI parses it back before replaying.
            if (session.isNotEmpty()) {
                val replay = runner.run(session.joinToString("\n") { it.text })
                if (replay.status != RunStatus.OK) {
                    return "$label: the session replay failed at `${replay.failedLine?.text ?: "a line"}`: " +
                        (replay.message ?: replay.steps.lastOrNull()?.let { StepFormatter.text(it) } ?: "")
                }
            }
            val result = runner.run(part.joinToString("\n") { it.text })
            steps.addAll(result.steps)
            if (result.status != RunStatus.OK) {
                return "$label failed after the session replay, where the single run passed:\n" +
                    StepFormatter.text(listOfNotNull(result.steps.lastOrNull()))
            }
            session.addAll(result.executed)
            last = runner
        }
        val splitText = StepFormatter.text(steps)
        val singleText = StepFormatter.text(whole.steps)
        if (splitText != singleText) {
            return "$name: the steps of a run split into $parts parts differ from one run; " +
                firstDifference(singleText, splitText, "one run" to "split")
        }
        if (last != null && single.stateDump != last.stateDump) {
            return "$name: the state after a run split into $parts parts differs from one run's; " +
                firstDifference(single.stateDump, last.stateDump, "one run" to "split")
        }
        return null
    }

    public companion object {
        private const val SHOWN_LEAKS = 20

        /**
         * A step as an agent reads it, without the echo of the command it ran: [noStepShows]'s view of a step, public
         * so an app's own privacy tests (of a state dump, say) read steps the same way.
         */
        public fun summaryText(step: StepRecord): String = StepFormatter.text(step).split("\n").drop(1).joinToString("\n")

        /** Every backtick-quoted `*.appctl` file name in [text], without its path. */
        internal fun scenarioNames(text: String): Set<String> =
            Regex("`(?:[^`\\s]*/)?([A-Za-z0-9_.\\-]+\\.appctl)`").findAll(text).map { it.groupValues[1] }.toSet()

        /** `first difference at line N:` then both lines, so a failure shows where to look. */
        internal fun firstDifference(a: String, b: String, labels: Pair<String, String>): String {
            val left = a.split("\n")
            val right = b.split("\n")
            val index = left.zip(right).indexOfFirst { (l, r) -> l != r }.takeIf { it >= 0 } ?: minOf(left.size, right.size)
            fun describe(lines: List<String>) = if (index < lines.size) lines[index] else "(ends)"
            return "first difference at line ${index + 1}:\n  ${labels.first}: ${describe(left)}\n  ${labels.second}: ${describe(right)}"
        }

        /** [lines] in [parts] consecutive runs of near-equal length (fewer when there are fewer lines). */
        internal fun split(lines: List<ScriptLine>, parts: Int): List<List<ScriptLine>> {
            val count = minOf(parts, lines.size)
            if (count <= 0) return emptyList()
            return (0 until count).map { index -> lines.subList(index * lines.size / count, (index + 1) * lines.size / count) }
        }
    }
}
