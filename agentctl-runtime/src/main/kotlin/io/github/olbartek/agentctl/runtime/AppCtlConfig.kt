package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.CLIName
import io.github.olbartek.agentctl.DocsText
import io.github.olbartek.agentctl.MockLatency
import io.github.olbartek.agentctl.MockMethod
import io.github.olbartek.agentctl.ScreenDoc
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The Gradle tasks the verification ladder runs, from the repository root. The counterpart of the reference's
 * SwiftPM packages and `xcodebuild` schemes: the CLI assumes no module layout and learns it only here.
 */
public data class GradleTasks(
    /** L0: what must build, e.g. `:app:assembleDebug`. */
    val build: List<String> = listOf("assembleDebug"),
    /** L1: the unit tests, e.g. `test`. */
    val test: List<String> = listOf("test"),
    /** L3: the screenshot tests against their references, e.g. `verifyRoborazziDebug`. Empty: no L3. */
    val snapshotsVerify: List<String> = emptyList(),
    /** `snapshots --record`: re-record the references, e.g. `recordRoborazziDebug`. */
    val snapshotsRecord: List<String> = emptyList(),
    /** Where the references live, relative to the root, for the review hint after recording. */
    val snapshotReferences: List<String> = emptyList(),
    /** `app launch` and L4: build and install the debug app on the device, e.g. `:app:installDebug`. */
    val install: String = "installDebug",
)

/** L4 of `check --ui`: the app is launched seeded on a device, then one scenario runs through its agent bridge. */
public data class AppCheck(
    /** Commands to seed the app with before the scenario (`appctl-seed`). */
    val seed: String? = null,
    /** The scenario to send through the bridge, without its extension. `null` uses the first scenario file. */
    val scenario: String? = null,
    /** The screen path the app must be on afterwards. `null` skips that assertion. */
    val expectScreen: String? = null,
)

/**
 * The example commands printed in the CLI's help pages. The defaults are placeholders (`<command>`, `<path>`) that
 * hold for every host, so a published CLI never advertises another app's screens.
 */
public data class HelpExamples(
    /** How the examples spell the CLI, e.g. `./appctl` for a repo whose wrapper script rebuilds it first. */
    val invocation: String = CLIName.current,
    /** An extra paragraph for the top-level help. `null` prints none. */
    val note: String? = null,
    /** The scripts in the three `run` examples: a plain run, a `--session` run, and a `--json` run. */
    val runScripts: List<String> = listOf("<command>; <command>", "<command>", "expect screen=<path>"),
    /** The `--session` file in the `run` and `state` examples. */
    val sessionPath: String = ".appctl/s1.session",
    /** The scenario file in the `test` example. `null` uses `<scenariosPath>/<name>.appctl`. */
    val scenarioPath: String? = null,
    /** The seeds in the two `app launch` examples: a plain seed, and one for `--no-build`. */
    val appSeeds: List<String> = listOf("<command>", "<command>; <command>"),
    /** The scripts in the two `app run` examples: a plain run, and a `--json` run. */
    val appScripts: List<String> = listOf("<command>; expect <key>=<value>", "<command>"),
) {
    public fun runScript(index: Int): String = runScripts.getOrElse(index) { "<command>" }

    public fun appSeed(index: Int): String = appSeeds.getOrElse(index) { "<command>" }

    public fun appScript(index: Int): String = appScripts.getOrElse(index) { "<command>" }
}

/**
 * Everything AgentCtl needs to know about a host app: the facts the CLI would otherwise hard-code, the data the
 * docs are rendered from, and the functions that build the app's stores. The same value drives the CLI
 * (`AgentCtl.run(config, args)`) and the in-app bridge (`AgentLaunch(config)`).
 */
public class AppCtlConfig<S, A>(
    /** The app's name, as it appears in the CLI's help. */
    public val name: String,
    /**
     * The file whose presence marks the repository root, where every path below is resolved from:
     * `settings.gradle.kts` by default.
     */
    public val rootMarker: String = "settings.gradle.kts",
    /** The debug app's application ID, for `adb`. */
    public val applicationId: String,
    /** The activity `app launch` starts: a class name, or one starting with `.` relative to [applicationId]. */
    public val launchActivity: String = ".MainActivity",
    public val gradle: GradleTasks = GradleTasks(),
    /** The device `app` and `check --ui` use by default: an `adb` serial or an AVD name; `null` for the only one. */
    public val device: String? = null,
    /** Where `test` and `check` look for `*.appctl` files, relative to the root. */
    public val scenariosPath: String = "scenarios",
    /** Where `docs` writes the generated command reference, relative to the root. */
    public val docsPath: String = "docs/agent-commands.md",
    /** Where the CLI writes logs, screenshots and session files, relative to the root. Keep it out of git. */
    public val outputPath: String = ".appctl",
    public val appCheck: AppCheck = AppCheck(),
    public val help: HelpExamples = HelpExamples(),
    public val mockMethods: List<MockMethod>,
    public val docsText: DocsText,
    public val screens: List<ScreenDoc>,
    /** A fresh deterministic store: `run`, `test`, `check` and the host's own tests. */
    public val makeHeadless: () -> HeadlessHost<S, A>,
    /** The app's store behind its agent bridge: real clock, real mock latency, on `dispatcher`. */
    public val makeLive: (latency: MockLatency, dispatcher: CoroutineDispatcher) -> LiveHost<S, A>,
    /** Called for `clear-session` before the app launches. */
    public val clearSession: () -> Unit = {},
) {
    /** A fresh deterministic headless runner. */
    public fun makeRunner(): ScriptRunner<S, A> = makeHeadless().makeRunner()

    /** Runs each scenario file against its own fresh runner. */
    public suspend fun runScenarios(files: List<java.io.File>): List<ScenarioResult> =
        files.map { file -> ScenarioRunner.run(file) { makeRunner() } }
}
