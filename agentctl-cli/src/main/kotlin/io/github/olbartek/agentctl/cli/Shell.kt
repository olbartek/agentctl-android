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
            val process = builder.start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                process.exitValue()
            }
        } catch (_: IOException) {
            -1
        }
    }

    /** Runs a command and returns its standard output (standard error is discarded), or `null` if it failed to run. */
    fun capture(command: List<String>, directory: File? = null, timeoutSeconds: Long = 60): String? = try {
        val process = ProcessBuilder(command)
            .apply { if (directory != null) directory(directory) }
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) output else null
    } catch (_: IOException) {
        null
    }

    /** Runs a command and writes its standard output, byte for byte, to `file`. */
    fun captureTo(command: List<String>, file: File, timeoutSeconds: Long = 60): Int = try {
        file.parentFile?.mkdirs()
        val process = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(file)
            .start()
        if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) process.exitValue() else -1
    } catch (_: IOException) {
        -1
    }
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
