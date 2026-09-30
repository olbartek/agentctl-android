package io.github.olbartek.agentctl.cli

import io.github.olbartek.agentctl.ScreensRenderer
import io.github.olbartek.agentctl.ScriptLine
import io.github.olbartek.agentctl.StepFormatter
import io.github.olbartek.agentctl.StepRecord
import io.github.olbartek.agentctl.runtime.AppCtlConfig
import io.github.olbartek.agentctl.runtime.HttpParser
import io.github.olbartek.agentctl.runtime.RepoRoot
import io.github.olbartek.agentctl.runtime.RunStatus
import io.github.olbartek.agentctl.runtime.ScenarioRunner
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking

/** Where the CLI prints: stdout for results, stderr for errors (always prefixed `error: `). */
internal class Io(val out: PrintStream, val err: PrintStream) {
    fun print(text: String) = out.println(text)

    fun error(message: String) = err.println("error: $message")
}

/**
 * The implementations behind the subcommands. Headless runs happen on the calling thread, on the headless host's
 * virtual-time dispatcher, which makes every one of them deterministic.
 */
internal class Cli<S, A>(
    val config: AppCtlConfig<S, A>,
    val io: Io,
    val environment: Map<String, String>,
    val workingDirectory: File,
) {
    val invocation: String get() = config.help.invocation

    fun run(script: String, sessionPath: String?, diff: Boolean, json: Boolean): Int = runBlocking {
        val runner = config.makeRunner()
        val launch = runner.launch()
        // A launch that did not settle fails the run before the script (or a session replay) starts.
        if (launch.status != RunStatus.OK) {
            io.print(if (json) StepFormatter.json(listOf(launch.step)) else StepFormatter.text(launch.step))
            return@runBlocking launch.status.code
        }
        val steps = mutableListOf<StepRecord>()
        if (sessionPath != null) {
            val replay = runner.run(Session.load(resolve(sessionPath)))
            if (replay.status != RunStatus.OK) {
                val why = replay.message ?: replay.steps.lastOrNull()?.message ?: ""
                io.error("session replay of $sessionPath failed at ${replay.failedLine?.text ?: "a line"}: $why")
                return@runBlocking RunStatus.FAILED.code
            }
        } else {
            steps.add(launch.step)
        }
        runner.recordsDiff = diff
        val result = runner.run(script)
        steps.addAll(result.steps)
        if (json) {
            io.print(StepFormatter.json(steps))
        } else if (steps.isNotEmpty()) {
            io.print(StepFormatter.text(steps))
        }
        result.message?.let(io::error)
        if (sessionPath != null) Session.append(result.executed, resolve(sessionPath))
        result.status.code
    }

    fun state(sessionPath: String?): Int = runBlocking {
        val runner = config.makeRunner()
        val launch = runner.launch()
        if (launch.status != RunStatus.OK) {
            io.error("the app did not settle at launch\n" + StepFormatter.text(launch.step))
            return@runBlocking launch.status.code
        }
        if (sessionPath != null) {
            val replay = runner.run(Session.load(resolve(sessionPath)))
            if (replay.status != RunStatus.OK) {
                io.error("session replay of $sessionPath failed")
                return@runBlocking RunStatus.FAILED.code
            }
        }
        io.print(runner.stateDump)
        0
    }

    fun screens(): Int {
        io.print(ScreensRenderer.render(config.screens, config.docsText.mockExample))
        return 0
    }

    /** The generated command reference as it should be, rendered from the config. */
    val docsMarkdown: String get() = config.docsMarkdown

    fun docs(check: Boolean): Int {
        val root = root() ?: return RunStatus.INTERNAL_ERROR.code
        val path = config.docsPath
        val file = File(root, path)
        val generated = docsMarkdown
        val existing = if (file.isFile) file.readText() else null
        if (check) {
            if (existing != generated) {
                io.print(Message.staleDocs(config))
                return 1
            }
            io.print("$path is up to date.")
            return 0
        }
        try {
            file.parentFile?.mkdirs()
            file.writeText(generated)
        } catch (error: IOException) {
            io.error("cannot write ${file.path}: $error")
            return RunStatus.INTERNAL_ERROR.code
        }
        io.print(if (existing == generated) "$path was already up to date." else "Wrote $path.")
        return 0
    }

    /**
     * Exit codes (CONTRACT.md §5): 0 when every scenario passed; 1 when one failed; 3 when there was nothing to run
     * or a file could not be read — no scenario files where the config says they are, a scenario file named on the
     * command line that does not exist, or no repo root to look in.
     */
    fun test(paths: List<String>): Int = runBlocking {
        val files = scenarioFiles(paths) ?: return@runBlocking RunStatus.INTERNAL_ERROR.code
        val results = config.runScenarios(files)
        results.forEach { io.print(it.report) }
        val failed = results.count { !it.passed }
        io.print("${results.size - failed} passed, $failed failed")
        when {
            results.any { it.status == RunStatus.INTERNAL_ERROR } -> RunStatus.INTERNAL_ERROR.code
            failed == 0 -> 0
            else -> RunStatus.FAILED.code
        }
    }

    fun snapshots(record: Boolean): Int {
        val root = root() ?: return RunStatus.INTERNAL_ERROR.code
        val result = Snapshots(this, root).run(record)
        if (record) {
            io.print("${if (result.ok) "recorded" else "FAILED to record"} snapshots")
        } else {
            io.print("${if (result.ok) "ok" else "FAIL"} snapshot tests")
        }
        result.details.forEach(io::print)
        val references = config.gradle.snapshotReferences.ifEmpty { config.gradle.snapshotModules.map(Snapshots::directory) }
        if (record && result.ok && references.isNotEmpty()) {
            io.print("Review the changes: git status --short ${references.joinToString(" ")}")
        }
        return if (result.ok) 0 else 1
    }

    fun check(ui: Boolean, device: String?): Int {
        // L4 launches the app: a malformed APPCTL_PORT is a usage error before anything is built.
        if (ui && launchPort(null) == null) return RunStatus.USAGE.code
        val root = root() ?: return RunStatus.INTERNAL_ERROR.code
        return Ladder(this, root, ui, device).run()
    }

    fun appLaunch(seed: String?, device: String?, latency: Int?, clearSession: Boolean, build: Boolean, port: Int?): Int {
        val root = root() ?: return RunStatus.INTERNAL_ERROR.code
        val explicit = launchPort(port) ?: return RunStatus.USAGE.code
        return try {
            val launched = AppLauncher(this, root).launch(seed, device, latency, clearSession, build, explicit.port)
            io.print(launched.report)
            0
        } catch (error: AppCtlException) {
            io.error(error.message ?: "launch failed")
            error.status.code
        }
    }

    fun appRun(script: String, json: Boolean, port: Int?): Int = bridgeCall(port, "POST", if (json) "/run?format=json" else "/run", script)

    fun appTest(paths: List<String>, options: AppTest.Options): Int {
        val root = root() ?: return RunStatus.INTERNAL_ERROR.code
        val explicit = launchPort(options.port) ?: return RunStatus.USAGE.code
        return AppTest(this, root).run(paths, options.copy(port = explicit.port))
    }

    fun appGet(path: String, port: Int?): Int = bridgeCall(port, "GET", path, null)

    fun appScreenshot(file: String, device: String?): Int = onDevice { it.screenshot(file, device) }

    fun appRecordStart(file: String, device: String?): Int = onDevice { it.recordStart(file, device) }

    fun appRecordStop(): Int = onDevice { it.recordStop() }

    fun appStatusbar(clean: Boolean, device: String?): Int = onDevice { it.statusbar(clean, device) }

    fun appInfo(device: String?): Int = onDevice { it.info(device) }

    /** A device command: what it prints on success, or its error with its exit code (3 unless the name was ambiguous). */
    private fun onDevice(body: (DeviceCommands) -> String): Int {
        val root = root() ?: return RunStatus.INTERNAL_ERROR.code
        return try {
            io.print(body(DeviceCommands(this, root)))
            0
        } catch (error: AppCtlException) {
            io.error(error.message ?: "failed")
            error.status.code
        } catch (error: IOException) {
            io.error(error.toString())
            RunStatus.INTERNAL_ERROR.code
        }
    }

    /** A launch's port: `--port` or `APPCTL_PORT` exactly, or `null` inside to find a free one; `null` on a usage error. */
    private fun launchPort(flag: Int?): LaunchPort? = try {
        LaunchPort(Ports.explicit(flag, environment))
    } catch (error: Ports.BadEnvironmentPort) {
        io.error(error.message ?: "bad ${Ports.ENVIRONMENT_VARIABLE}")
        null
    } catch (error: Ports.BadFlagPort) {
        io.error(error.message ?: "bad --port")
        null
    }

    private class LaunchPort(val port: Int?)

    /**
     * A request to the bridge on `--port`, `APPCTL_PORT`, the port `app launch` recorded, or 8765. When the recorded
     * port does not answer, the message says the launch state is stale.
     */
    private fun bridgeCall(flag: Int?, method: String, path: String, body: String?): Int {
        val layout = quietRoot()?.let { Layout(it, config.outputPath) }
        val resolved = try {
            Ports.client(flag, environment) { layout?.let(BridgeState::load) }
        } catch (error: Ports.BadEnvironmentPort) {
            io.error(error.message ?: "bad ${Ports.ENVIRONMENT_VARIABLE}")
            return RunStatus.USAGE.code
        } catch (error: Ports.BadFlagPort) {
            io.error(error.message ?: "bad --port")
            return RunStatus.USAGE.code
        } catch (error: Unreadable) {
            io.error(Message.unreadableBridgeState(this, error.reason))
            return RunStatus.INTERNAL_ERROR.code
        }
        return try {
            val client = BridgeClient(resolved.port)
            val recorded = resolved.state
            if (recorded != null && method != "GET") {
                // A script changes the app it runs in, so it is only posted once the recorded app is known to answer;
                // a GET changes nothing, and its own answer is checked instead.
                val check = client.send("GET", "/snapshot")
                if (check.contradicts(recorded)) {
                    io.error(Message.anotherAppThanRecorded(this, resolved.port, check.app, check.platform, recorded))
                    return RunStatus.INTERNAL_ERROR.code
                }
            }
            val response = client.send(method, path, body)
            if (recorded != null && response.contradicts(recorded)) {
                io.error(Message.anotherAppThanRecorded(this, resolved.port, response.app, response.platform, recorded))
                return RunStatus.INTERNAL_ERROR.code
            }
            if (response.body.endsWith("\n")) io.out.print(response.body) else io.out.println(response.body)
            response.exitCode
        } catch (error: IOException) {
            val unreachable = Message.bridgeUnreachable(this, resolved.port, error)
            io.error(resolved.state?.let { unreachable + Message.staleBridgeState(this, it) } ?: unreachable)
            RunStatus.INTERNAL_ERROR.code
        }
    }

    // Shared helpers

    /** A path from the command line, relative to the working directory. */
    fun resolve(path: String): File = File(path).let { if (it.isAbsolute) it else File(workingDirectory, path) }

    /**
     * The files named on the command line, or else every scenario in the config's `scenariosPath`. `null`, with the
     * reason printed, when there is no repo root or that directory holds no scenario files: a run that checked
     * nothing must not report success.
     */
    fun scenarioFiles(paths: List<String>): List<File>? {
        if (paths.isNotEmpty()) return paths.map(::resolve)
        val root = root() ?: return null
        val directory = File(root, config.scenariosPath)
        val files = ScenarioRunner.files(directory)
        if (files.isEmpty()) {
            io.error(Message.noScenarios(directory))
            return null
        }
        return files
    }

    /**
     * `$APPCTL_ROOT` (set by the wrapper), or the nearest ancestor of the working directory containing the config's
     * root marker.
     */
    fun root(): File? {
        quietRoot()?.let { return it }
        io.error("cannot find the repo root (no ${config.rootMarker} above ${workingDirectory.absolutePath})")
        return null
    }

    /** [root], without the error when there is none: the `app` commands that only read the launch state can do without. */
    private fun quietRoot(): File? {
        environment["APPCTL_ROOT"]?.takeIf { it.isNotEmpty() }?.let { return File(it) }
        return RepoRoot.find(config.rootMarker, workingDirectory)
    }
}

/** `--session` files: one command per line, replayed before each run. */
internal object Session {
    fun load(file: File): String = if (file.isFile) file.readText() else ""

    fun append(lines: List<ScriptLine>, file: File) {
        file.parentFile?.mkdirs()
        if (lines.isEmpty()) {
            if (!file.exists()) file.createNewFile()
            return
        }
        file.appendText(lines.joinToString("\n") { it.text } + "\n")
    }
}

/** A failure outside the script engine: exit code 3. */
/** A failure the CLI reports as one `error:` line, and exits with [status] (3 unless the user asked for something wrong). */
internal class AppCtlException(message: String, val status: RunStatus = RunStatus.INTERNAL_ERROR) : Exception(message)

/** The messages that name the CLI itself. They spell it with the host's own invocation. */
internal object Message {
    fun bridgeUnreachable(cli: Cli<*, *>, port: Int, error: Exception): String =
        "cannot reach the app's agent bridge on 127.0.0.1:$port (is the app running? ${cli.invocation} app launch): $error"

    /** Appended to [bridgeUnreachable] when the port came from `bridge.json`: the app it recorded has gone. */
    fun staleBridgeState(cli: Cli<*, *>, state: BridgeState): String =
        "; the port is from ${cli.config.outputPath}/bridge.json (launched ${state.launchedAt.truncatedTo(ChronoUnit.SECONDS)} on " +
            "${state.device}), which is stale once that app has quit: relaunch with ${cli.invocation} app launch"

    /** How a bridge says who it is: `<app id> (<platform>)`, as agentctl-ios writes it. */
    private fun identity(app: String, platform: String?): String = "$app (${platform ?: "no ${HttpParser.PLATFORM_HEADER}"})"

    /** `app launch`: the bridge on [port] belongs to [other] on [platform], not to this config's [appId] on Android. */
    fun anotherApp(port: Int, other: String?, platform: String?, appId: String): String =
        "the app's agent bridge on 127.0.0.1:$port answers as ${other?.let { identity(it, platform) } ?: "an app without ${HttpParser.APP_HEADER}"}, " +
            "not ${identity(appId, BridgeState.PLATFORM)}: " +
            "another app holds that port; pass --port or set ${Ports.ENVIRONMENT_VARIABLE}" +
            // Without a header, the app may also be an installed one from before it (`--no-build`).
            when {
                other == null -> " (or the installed app predates ${HttpParser.APP_HEADER}: launch without --no-build)"
                platform == null -> " (or the installed app predates ${HttpParser.PLATFORM_HEADER}: launch without --no-build)"
                else -> ""
            }

    /** `app launch`: the bridge never answered, and something else listens on [port], so it could not listen there. */
    fun couldNotListen(port: Int): String =
        "the app's agent bridge could not listen on 127.0.0.1:$port: another process holds that port; " +
            "pass --port or set ${Ports.ENVIRONMENT_VARIABLE}"

    /** `app run`/`state`/`screens` on the recorded port: another app, or another platform's build of it, answers there now. */
    fun anotherAppThanRecorded(cli: Cli<*, *>, port: Int, other: String?, platform: String?, recorded: BridgeState): String =
        "the app's agent bridge on 127.0.0.1:$port answers as ${other?.let { identity(it, platform) } ?: "an app without ${HttpParser.APP_HEADER}"}, " +
            "not ${identity(recorded.appId, recorded.platform)} from ${cli.config.outputPath}/bridge.json, " +
            "which is stale: relaunch with ${cli.invocation} app launch"

    /** `bridge.json` is there but is not a launch state. */
    fun unreadableBridgeState(cli: Cli<*, *>, reason: String): String =
        "cannot read ${cli.config.outputPath}/bridge.json: $reason; relaunch with ${cli.invocation} app launch, or pass --port"

    /** `test` and L2 with nothing to run. Zero scenarios passing is not a pass. */
    fun noScenarios(directory: File): String =
        "no scenario files (*.appctl) in ${directory.path}: check the config's scenariosPath, or name the files to run"

    /** `adb devices` itself timed out: the adb server, not a device, is stuck. As the reference words a stuck simctl. */
    fun adbDidNotAnswer(seconds: Long): String =
        "adb devices did not answer within $seconds s; the adb server may be stuck: run 'adb kill-server', or restart the emulator"

    // `app screenshot`, `app record`, `app statusbar` and `app info`: the reference's words, with the device's.

    fun saved(file: File, device: Device): String = "saved ${file.path} (${device.label} [${device.serial}])"

    fun recording(cli: Cli<*, *>, file: File, device: Device): String =
        "recording ${file.path} (${device.label} [${device.serial}]); stop with ${cli.invocation} app record stop"

    fun recordingRunning(cli: Cli<*, *>, state: RecordState): String =
        "a recording is already running: ${state.file} (started ${state.startedAt}); stop it with ${cli.invocation} app record stop"

    fun recorded(file: String, seconds: Double): String = "recorded $file (${String.format(java.util.Locale.ROOT, "%.1f", seconds)}s)"

    fun noRecording(cli: Cli<*, *>): String = "no recording to stop (no ${cli.config.outputPath}/${RecordState.FILE_NAME})"

    fun recordingGone(state: RecordState): String = "the recording of ${state.file} is no longer running"

    fun recorderDidNotFinish(cli: Cli<*, *>, state: RecordState): String =
        "the recorder of ${state.file} did not finish within 30 s; try ${cli.invocation} app record stop again"

    fun recordingNotWritten(cli: Cli<*, *>, state: RecordState): String =
        "the recording ${state.file} was not written; see ${cli.config.outputPath}/logs/app-record.log"

    fun statusBar(clean: Boolean, device: Device): String =
        "status bar ${if (clean) "clean" else "reset"} on ${device.label} [${device.serial}]"

    fun notInstalled(cli: Cli<*, *>, device: Device): String =
        "${cli.config.applicationId} is not installed on ${device.label} [${device.serial}]; run ${cli.invocation} app launch"

    /** An AVD that is not running: it has no serial yet, so its bracket says what it is. */
    fun notBooted(cli: Cli<*, *>, label: String, id: String): String =
        "$label [$id] is not booted; boot it, or run ${cli.invocation} app launch"

    /**
     * `--device` names several running devices (two emulators of one AVD, two phones of one model): none is picked. Newest
     * Android release first, then by serial, as agentctl-ios lists simulators (newest runtime, then UDID).
     */
    fun severalDevices(name: String, devices: List<Device>): String {
        val release = { device: Device -> device.release.split('.').map { it.toIntOrNull() ?: 0 } }
        val newestFirst = Comparator<Device> { a, b ->
            val (x, y) = release(a) to release(b)
            (0 until maxOf(x.size, y.size)).map { (y.getOrElse(it) { 0 }).compareTo(x.getOrElse(it) { 0 }) }.firstOrNull { it != 0 } ?: 0
        }.thenBy { it.serial }
        return "several devices are named '$name'; pass --device <serial>:\n" +
            devices.sortedWith(newestFirst).joinToString("\n") { "  ${it.serial}  ${it.label}" }
    }

    /** `--device` names a device that is running but frozen: it is not booted again beside itself. */
    fun deviceDoesNotAnswer(name: String, serial: String): String =
        "$name is running as $serial but does not answer (adb shell timed out after ${Devices.PROBE_SECONDS} s): " +
            "restart it (adb -s $serial emu kill), or pass another --device"

    fun staleDocs(config: AppCtlConfig<*, *>): String = "${config.docsPath} is stale. Run ${config.help.invocation} docs."

    /** The `check` ladder's one-column form of [staleDocs]. */
    fun staleDocsDetail(config: AppCtlConfig<*, *>): String = "stale: run ${config.help.invocation} docs"
}
