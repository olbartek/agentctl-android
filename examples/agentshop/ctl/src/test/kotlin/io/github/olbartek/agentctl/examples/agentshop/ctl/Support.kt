package io.github.olbartek.agentctl.examples.agentshop.ctl

import io.github.olbartek.agentctl.cli.AgentCtl
import io.github.olbartek.agentctl.runtime.ScenarioRunner
import io.github.olbartek.agentctl.runtime.ScriptRunner
import io.github.olbartek.agentctl.examples.agentshop.app.AppFeature
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/** examples/agentshop: the example's root, where the scenarios and the committed command reference live. */
val exampleRoot: File = File(System.getProperty("agentshop.root") ?: error("agentshop.root is not set"))

val scenariosDirectory: File get() = File(exampleRoot, AgentShopConfig.appCtl.scenariosPath)

val scenarioFiles: List<File> get() = ScenarioRunner.files(scenariosDirectory)

/** A fresh deterministic runner on AgentShop's headless wiring. */
fun headlessRunner(): ScriptRunner<AppFeature.State, AppFeature.Action> = AgentShopConfig.headless().makeRunner()

/** What one CLI invocation printed, and its exit code. */
data class CliResult(val out: String, val err: String, val status: Int) {
    val combined: String get() = out + err
}

/** Runs `shopctl` in-process, with the example's root as `APPCTL_ROOT`, as the wrapper runs it. */
fun shopctl(vararg args: String): CliResult {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val status = AgentCtl.run(
        AgentShopConfig.appCtl,
        args.toList(),
        PrintStream(out, true, Charsets.UTF_8),
        PrintStream(err, true, Charsets.UTF_8),
        mapOf("APPCTL_ROOT" to exampleRoot.absolutePath),
        exampleRoot,
    )
    return CliResult(out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8), status)
}

/** A test resource, as text: the iOS transcripts live under `ios/`. */
fun resource(path: String): String? =
    Thread.currentThread().contextClassLoader.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
