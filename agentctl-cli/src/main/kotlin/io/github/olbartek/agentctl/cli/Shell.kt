package io.github.olbartek.agentctl.cli

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
    /** Runs a command with its output in `log`; returns the exit status, or -1 if it could not start. */
    fun run(
        command: List<String>,
        directory: File,
        log: File,
        append: Boolean = false,
        environment: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 3600,
    ): Int {
        log.parentFile?.mkdirs()
        val builder = ProcessBuilder(command)
            .directory(directory)
            .redirectErrorStream(true)
            .redirectOutput(if (append) ProcessBuilder.Redirect.appendTo(log) else ProcessBuilder.Redirect.to(log))
        builder.environment().putAll(environment)
        return try {
            finish(builder.start(), timeoutSeconds) ?: run {
                // Said where the command's own output ends, so a log that stops short explains itself.
                log.appendText("\nagentctl: ${command.joinToString(" ")} did not finish within $timeoutSeconds s; stopped it\n")
                -1
            }
        } catch (_: IOException) {
            -1
        }
    }

    /**
     * Runs a command and returns its standard output (standard error is discarded), or `null` if it failed to run or
     * did not finish within [timeoutSeconds]. The output goes to a file rather than a pipe read to its end first, so
     * the timeout holds even for a command that never closes its output (`adb shell` on a frozen emulator).
     */
    fun capture(command: List<String>, directory: File? = null, timeoutSeconds: Long = 60): String? {
        val output = try {
            File.createTempFile("appctl-capture", ".out")
        } catch (_: IOException) {
            return null
        }
        return try {
            val process = ProcessBuilder(command)
                .apply { if (directory != null) directory(directory) }
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(output)
                .start()
            if (finish(process, timeoutSeconds) == null) null else output.readText()
        } catch (_: IOException) {
            null
        } finally {
            output.delete()
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
        val all = process.descendants().toList() + process.toHandle()
        all.forEach { it.destroy() }
        if (!process.waitFor(KILL_AFTER_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        all.filter { it.isAlive }.forEach { it.destroyForcibly() }
        process.waitFor(5, TimeUnit.SECONDS)
        return null
    }

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
    fun directory(root: File, environment: Map<String, String>): File? {
        environment["ANDROID_HOME"]?.takeIf { it.isNotEmpty() }?.let { return File(it) }
        environment["ANDROID_SDK_ROOT"]?.takeIf { it.isNotEmpty() }?.let { return File(it) }
        val local = File(root, "local.properties")
        if (local.isFile) {
            val properties = Properties().apply { local.inputStream().use(::load) }
            properties.getProperty("sdk.dir")?.let { return File(it) }
        }
        return null
    }

    fun tool(root: File, environment: Map<String, String>, relativePath: String, name: String): String {
        val candidate = directory(root, environment)?.let { File(it, relativePath) }
        return if (candidate != null && candidate.canExecute()) candidate.path else name
    }

    fun adb(root: File, environment: Map<String, String>) = tool(root, environment, "platform-tools/adb", "adb")

    fun emulator(root: File, environment: Map<String, String>) = tool(root, environment, "emulator/emulator", "emulator")
}
