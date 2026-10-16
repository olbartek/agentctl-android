package io.github.olbartek.agentctl.cli

import io.github.olbartek.agentctl.runtime.AgentLaunchOptions
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Every directory the CLI writes to, derived from the repo root and the host's config. */
internal class Layout(val root: File, outputPath: String) {
    /** Where logs, screenshots and session files go. */
    val output = File(root, outputPath)
    val logs = File(output, "logs")
    val screenshots = File(output, "screenshots")
}

internal object Shell {
    /** What [run] returns for a command that could not be started or did not finish in time. */
    const val NOT_RUN: Int = -1

    /** Runs a command with its output in `log`; returns the exit status, or [NOT_RUN] if it could not start or did not finish. */
    fun run(
        command: List<String>,
        directory: File,
        log: File,
        append: Boolean = false,
        environment: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 3600,
    ): Int {
        // The log may hold a launch's command line: its owner's alone.
        PrivateFiles.prepareLog(log)
        val builder = ProcessBuilder(command)
            .directory(directory)
            .redirectErrorStream(true)
            .redirectOutput(if (append) ProcessBuilder.Redirect.appendTo(log) else ProcessBuilder.Redirect.to(log))
        builder.environment().putAll(environment)
        return try {
            finish(builder.start(), timeoutSeconds) ?: run {
                // Said where the command's own output ends, so a log that stops short explains itself.
                log.appendText("\nagentctl: ${shown(command)} did not finish within $timeoutSeconds s; stopped it\n")
                NOT_RUN
            }
        } catch (_: IOException) {
            NOT_RUN
        }
    }

    /**
     * [command] as a log or a message shows it: its words joined with spaces, and the value after `agent-token` (a
     * launch's token, in `am start`'s extras) as `<token>`.
     */
    fun shown(command: List<String>): String =
        command.mapIndexed { index, word -> if (index > 0 && command[index - 1] == AgentLaunchOptions.TOKEN) "<token>" else word }
            .joinToString(" ")

    /**
     * Runs a command and returns its standard output (standard error is discarded), or `null` if it failed to run or
     * did not finish within [timeoutSeconds]. The output goes to a file rather than a pipe read to its end first, so
     * the timeout holds even for a command that never closes its output (`adb shell` on a frozen emulator).
     */
    fun capture(command: List<String>, directory: File? = null, timeoutSeconds: Long = 60): String? =
        captureResult(command, directory, timeoutSeconds)?.output

    /** What [captureResult] read: the standard output, the exit status and the standard error. */
    data class Captured(val output: String, val status: Int, val error: String)

    /**
     * [capture], with the exit status and standard error, for a caller that reports why a command failed; `null` if
     * it failed to run or did not finish within [timeoutSeconds].
     */
    fun captureResult(command: List<String>, directory: File? = null, timeoutSeconds: Long = 60): Captured? {
        val output = try {
            File.createTempFile("appctl-capture", ".out")
        } catch (_: IOException) {
            return null
        }
        val error = try {
            File.createTempFile("appctl-capture", ".err")
        } catch (_: IOException) {
            output.delete()
            return null
        }
        return try {
            val process = ProcessBuilder(command)
                .apply { if (directory != null) directory(directory) }
                .redirectError(error)
                .redirectOutput(output)
                .start()
            finish(process, timeoutSeconds)?.let { status -> Captured(output.readText(), status, error.readText()) }
        } catch (_: IOException) {
            null
        } finally {
            output.delete()
            error.delete()
        }
    }

    /** Runs a command and writes its standard output, byte for byte, to `file`; -1 if it did not finish in time. */
    fun captureTo(command: List<String>, file: File, timeoutSeconds: Long = 60): Int = try {
        file.parentFile?.mkdirs()
        val process = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(file)
            .start()
        finish(process, timeoutSeconds) ?: -1
    } catch (_: IOException) {
        -1
    }

    /**
     * The exit status, or `null` after stopping the process and what it started when it runs out of time: SIGTERM,
     * then SIGKILL [KILL_AFTER_SECONDS] later for whatever ignored it.
     */
    private fun finish(process: Process, timeoutSeconds: Long): Int? {
        if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) return process.exitValue()
        val signalled = stoppable(process)
        signalled.forEach { it.destroy() }
        if (!process.waitFor(KILL_AFTER_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        // The first list too: a child whose parent died on SIGTERM is reparented, no longer a descendant. Taken again
        // as well: a parent that outlived SIGTERM may have started more.
        (signalled + stoppable(process)).distinct().filter { it.isAlive }.forEach { it.destroyForcibly() }
        process.waitFor(5, TimeUnit.SECONDS)
        return null
    }

    /**
     * A process that ran out of time and what it started, but never an adb server: an adb client that finds none
     * starts one, which stays its child until the client exits, and every later adb command needs it.
     */
    private fun stoppable(process: Process): List<ProcessHandle> =
        process.descendants().toList().filter { "fork-server" !in it.info().commandLine().orElse("") } + process.toHandle()

    /** How long a command that ran out of time gets to end on SIGTERM before it is killed. */
    private const val KILL_AFTER_SECONDS: Long = 2
}

/** Gradle, through the repository's own wrapper. */
internal object Gradle {
    fun command(root: File, tasks: List<String>): List<String> {
        val wrapper = File(root, "gradlew")
        val gradle = if (wrapper.canExecute()) wrapper.path else "gradle"
        return listOf(gradle, "--console=plain") + tasks
    }
}

/** The Android SDK's tools, found the way Android Studio and Gradle find the SDK. */
internal object AndroidSdk {
    fun directory(root: File, environment: Map<String, String>): File? = located(root, environment)?.first

    /** The SDK's directory, and where it came from: `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `local.properties' sdk.dir`. */
    private fun located(root: File, environment: Map<String, String>): Pair<File, String>? {
        for (variable in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            environment[variable]?.takeIf { it.isNotEmpty() }?.let { return File(it) to variable }
        }
        val local = File(root, "local.properties")
        if (local.isFile) {
            val properties = Properties().apply { local.inputStream().use(::load) }
            properties.getProperty("sdk.dir")?.let { return File(it) to "local.properties' sdk.dir" }
        }
        return null
    }

    /**
     * Why [adb] fell back to the `adb` on PATH, for the error when that cannot run either; `null` when the SDK has an
     * adb, which then failed to run for a reason of its own.
     */
    fun missingAdb(root: File, environment: Map<String, String>): String? {
        val (sdk, source) = located(root, environment) ?: return "no Android SDK found: set ANDROID_HOME, or sdk.dir in local.properties"
        if (File(sdk, "platform-tools/adb").canExecute()) return null
        return "no adb in ${File(sdk, "platform-tools").path} (from $source), nor on PATH: set ANDROID_HOME, or sdk.dir in " +
            "local.properties, to the Android SDK"
    }

    fun tool(root: File, environment: Map<String, String>, relativePath: String, name: String): String {
        val candidate = directory(root, environment)?.let { File(it, relativePath) }
        return if (candidate != null && candidate.canExecute()) candidate.path else name
    }

    fun adb(root: File, environment: Map<String, String>) = tool(root, environment, "platform-tools/adb", "adb")

    fun emulator(root: File, environment: Map<String, String>) = tool(root, environment, "emulator/emulator", "emulator")
}
