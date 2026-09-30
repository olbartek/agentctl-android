package io.github.olbartek.agentctl.cli

import io.github.olbartek.agentctl.AppTestSkip
import io.github.olbartek.agentctl.ScriptError
import io.github.olbartek.agentctl.ScriptLine
import io.github.olbartek.agentctl.ScriptParser
import io.github.olbartek.agentctl.runtime.RunStatus
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * `app test`: the scenario files, run in the real app on a device through its agent bridge, one fresh launch each —
 * what `test` does headlessly.
 */
internal class AppTest(private val cli: Cli<*, *>, private val root: File) {
    data class Options(
        val device: String?,
        /**
         * The mock latency, in ms. `null` is 0, as for L4: the bridge answers as soon as it listens, while the first
         * screen's own appearance may still be loading, and a scenario's first `expect` reads the state as it is.
         */
        val latency: Int?,
        val build: Boolean,
        /** `--port` (or `APPCTL_PORT`): launch on exactly this port; `null` finds a free one for each launch. */
        val port: Int?,
        /** Record the whole run to this `.mp4`, with a chapters file next to it. */
        val record: String?,
        /** Send the scenario one line at a time, this many seconds apart, so a recording can be followed. */
        val stepDelay: Double?,
    )

    private val io = cli.io

    /**
     * Exit codes (CONTRACT.md §5): 0 when every scenario that ran passed; 1 when one failed; 3 when there was nothing
     * to run, the app could not be built or launched, or its bridge did not answer.
     */
    fun run(paths: List<String>, options: Options): Int {
        val files = cli.scenarioFiles(paths) ?: return RunStatus.INTERNAL_ERROR.code
        val launcher = AppLauncher(cli, root)
        val device = try {
            launcher.resolve(options.device)
        } catch (error: AppCtlException) {
            io.error(error.message ?: "no device")
            return RunStatus.INTERNAL_ERROR.code
        }
        val recording = options.record?.let { path ->
            try {
                Recording.start(AndroidSdk.adb(root, cli.environment), device, cli.resolve(path), Layout(root, cli.config.outputPath))
            } catch (error: AppCtlException) {
                io.error(error.message ?: "recording failed")
                return RunStatus.INTERNAL_ERROR.code
            }
        }
        val results = mutableListOf<Result>()
        try {
            var built = !options.build
            for (file in files) {
                val name = file.nameWithoutExtension
                val source = try {
                    file.readText()
                } catch (_: IOException) {
                    null
                }
                val result = when {
                    source == null -> Result(name, Outcome.Broken("cannot read ${file.path}"))
                    AppTestSkip.reason(source) != null -> Result(name, Outcome.Skipped(AppTestSkip.reason(source)!!))
                    else -> {
                        recording?.chapter(name)
                        // Built once the first launch has really run with the build, not merely been attempted: a parse error
                        // or a failed install would otherwise leave every later file running a stale app.
                        runScenario(name, source, launcher, device, build = !built, options) { built = true }
                    }
                }
                results.add(result)
                io.print(result.report)
            }
        } finally {
            recording?.stop()?.let(io::print)
        }
        io.print(summary(results))
        return when {
            results.any { it.outcome is Outcome.Broken } -> RunStatus.INTERNAL_ERROR.code
            results.any { it.outcome is Outcome.Failed } -> RunStatus.FAILED.code
            else -> 0
        }
    }

    /** A fresh launch with no saved session, then the script through the bridge: whole, or a line at a time. */
    private fun runScenario(
        name: String,
        source: String,
        launcher: AppLauncher,
        device: Device,
        build: Boolean,
        options: Options,
        onLaunched: () -> Unit,
    ): Result {
        val start = TimeSource.Monotonic.markNow()
        val lines = try {
            ScriptParser.parse(source)
        } catch (error: ScriptError) {
            return Result(name, Outcome.Failed(error.line, "parse error: ${error.description}"))
        }
        // Each launch finds its own free port (unless one was given) and records it in bridge.json.
        val launched = try {
            launcher.launch(seed = null, device = device.serial, latency = options.latency ?: 0, clearSession = true, build = build, port = options.port)
        } catch (error: AppCtlException) {
            return Result(name, Outcome.Broken("launch failed: ${error.message}"))
        }
        onLaunched()
        val client = BridgeClient(launched.port)
        // One request for the script, or one per line: the app keeps one runner across requests, so `expect` still
        // sees the calls of the step before it.
        val requests = if (options.stepDelay == null) listOf(source) else lines.map { it.text }
        val body = StringBuilder()
        var exitCode = 0
        for ((index, request) in requests.withIndex()) {
            if (options.stepDelay != null && index > 0) Thread.sleep((options.stepDelay * 1000).toLong().coerceAtLeast(0))
            try {
                val response = client.send("POST", "/run", request)
                body.append(response.body)
                exitCode = response.exitCode
            } catch (error: IOException) {
                return Result(name, Outcome.Broken(Message.bridgeUnreachable(cli, launched.port, error)))
            }
            if (exitCode != 0) break
        }
        return result(name, lines, body.toString(), exitCode, start.elapsedNow())
    }

    sealed interface Outcome {
        data class Passed(val steps: Int, val duration: Duration) : Outcome

        data class Failed(val line: Int?, val text: String) : Outcome

        data class Skipped(val reason: String) : Outcome

        /** Not the scenario's fault: the app did not launch, or the file could not be read. */
        data class Broken(val message: String) : Outcome
    }

    data class Result(val name: String, val outcome: Outcome) {
        /**
         * `PASS name (N steps, X ms)`, `FAIL name:line` with the failing step, or `SKIP name: reason`: the headless
         * `test`'s lines, plus skips.
         */
        val report: String
            get() = when (outcome) {
                is Outcome.Passed -> "PASS $name (${outcome.steps} steps, ${outcome.duration.inWholeMilliseconds} ms)"
                is Outcome.Failed ->
                    (listOf("FAIL $name${outcome.line?.let { ":$it" } ?: ""}") + outcome.text.split("\n").filter { it.isNotEmpty() }.map { "  $it" })
                        .joinToString("\n")
                is Outcome.Skipped -> "SKIP $name: ${outcome.reason}"
                is Outcome.Broken -> "FAIL $name\n  ${outcome.message}"
            }
    }

    /**
     * `adb shell screenrecord` for the whole run, and a chapters file with the time each scenario started.
     * `screenrecord` stops after three minutes, so the run is recorded in back-to-back chunks, which are joined with
     * `ffmpeg` when it is installed and kept as numbered parts when it is not. Chapters are timed from the start of
     * the run, so on a run longer than one chunk they drift later than the joined video by the gaps between chunks
     * (about a second each).
     */
    class Recording private constructor(
        private val adb: String,
        private val device: Device,
        private val video: File,
        private val work: File,
    ) {
        private val chaptersFile = File(video.path + ".chapters.txt")
        private val started = TimeSource.Monotonic.markNow()
        private val chapters = mutableListOf<String>()
        private val chunks = mutableListOf<String>()

        @Volatile private var stopping = false

        @Volatile private var current: Process? = null
        private val recorder = Thread(::record, "appctl-screenrecord").apply { isDaemon = true }

        private fun record() {
            var index = 0
            while (!stopping) {
                val remote = "/sdcard/appctl-record-${index++}.mp4"
                synchronized(chunks) { chunks.add(remote) }
                val chunkStarted = TimeSource.Monotonic.markNow()
                val process = ProcessBuilder(adb, "-s", device.serial, "shell", "screenrecord", "--time-limit", "$CHUNK_SECONDS", remote)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(File(work, "screenrecord.log")))
                    .start()
                current = process
                val status = process.waitFor()
                // A chunk that ends early and badly means screenrecord cannot run (the device went away, say):
                // stop rather than respawn adb as fast as it exits.
                if (!stopping && status != 0 && chunkStarted.elapsedNow() < CHUNK_SECONDS.seconds / 2) {
                    stopping = true
                }
            }
        }

        fun chapter(name: String) {
            chapters.add("${timestamp(started.elapsedNow())} $name")
        }

        /** Stops the recording (`screenrecord` finishes its file on SIGINT), pulls it and writes the chapters. */
        fun stop(): String {
            stopping = true
            // Give the last chunk a moment so the last frames are in it, then interrupt it.
            Thread.sleep(500)
            // Again if a chunk started just as the run ended.
            var attempts = 0
            while (recorder.isAlive && attempts++ < 3) {
                Shell.capture(listOf(adb, "-s", device.serial, "shell", "pkill", "-2", "screenrecord"))
                current?.waitFor(10, TimeUnit.SECONDS)
                recorder.join(2000)
            }
            val remote = synchronized(chunks) { chunks.toList() }
            val parts = remote.mapIndexed { index, path ->
                val local = File(work, "part-$index.mp4")
                Shell.run(listOf(adb, "-s", device.serial, "pull", path, local.path), work, File(work, "pull.log"), append = true)
                Shell.run(listOf(adb, "-s", device.serial, "shell", "rm", "-f", path), work, File(work, "pull.log"), append = true)
                local
            }.filter { it.isFile && it.length() > 0 }
            chaptersFile.writeText(chapters.joinToString("\n") + "\n")
            if (parts.isEmpty()) return "warning: nothing was recorded; see ${File(work, "screenrecord.log").path}"
            val files = try {
                join(parts)
            } catch (error: IOException) {
                return "warning: the recording could not be saved to ${video.path}: ${error.message}"
            }
            return "recorded ${files.joinToString(", ") { it.path }} (chapters: ${chaptersFile.name})"
        }

        /** The chunks as one video, or as numbered parts beside where it would be if `ffmpeg` cannot join them. */
        private fun join(parts: List<File>): List<File> {
            if (parts.size == 1) return listOf(parts[0].copyTo(video, overwrite = true))
            val list = File(work, "parts.txt").apply { writeText(parts.joinToString("\n") { "file '${it.absolutePath}'" } + "\n") }
            val status = Shell.run(
                listOf("ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list.path, "-c", "copy", video.path),
                work,
                File(work, "ffmpeg.log"),
            )
            if (status == 0) return listOf(video)
            return parts.mapIndexed { index, part -> part.copyTo(File(video.parentFile, "${video.nameWithoutExtension}.part${index + 1}.mp4"), overwrite = true) }
        }

        companion object {
            /** `screenrecord`'s own limit is 180 seconds. */
            const val CHUNK_SECONDS = 180

            /** Starts recording, and returns once the first chunk is being written. */
            fun start(adb: String, device: Device, video: File, layout: Layout): Recording {
                video.absoluteFile.parentFile?.mkdirs()
                val work = File(layout.output, "recording").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val available = Shell.capture(listOf(adb, "-s", device.serial, "shell", "which", "screenrecord"))
                if (available.isNullOrBlank()) throw AppCtlException("screenrecord is not available on ${device.label}")
                val recording = Recording(adb, device, video.absoluteFile, work)
                recording.recorder.start()
                // screenrecord prints nothing when it starts; give it a moment to write its first frames.
                Thread.sleep(1000)
                if (recording.current?.isAlive != true) {
                    recording.stopping = true
                    Shell.capture(listOf(adb, "-s", device.serial, "shell", "pkill", "-2", "screenrecord"))
                    throw AppCtlException("adb shell screenrecord did not start; log: ${File(work, "screenrecord.log").path}")
                }
                return recording
            }
        }
    }

    companion object {
        /** What one run through the bridge amounts to, from the steps it printed and its exit code. */
        fun result(name: String, lines: List<ScriptLine>, body: String, exitCode: Int, duration: Duration): Result {
            val blocks = stepBlocks(body)
            if (exitCode != 0) {
                // The failing step is the last one printed, and it ran the script's line with the same index.
                val line = if (blocks.isEmpty()) null else lines.getOrNull(blocks.size - 1)?.line
                val error = body.split("\n").lastOrNull { it.startsWith("error: ") }
                return Result(name, Outcome.Failed(line, blocks.lastOrNull() ?: error ?: body))
            }
            return Result(name, Outcome.Passed(blocks.size, duration))
        }

        /** The steps in a text response: each starts at a `> ` line and runs to the next. */
        fun stepBlocks(body: String): List<String> {
            val blocks = mutableListOf<MutableList<String>>()
            for (line in body.split("\n")) {
                if (line.startsWith("> ")) {
                    blocks.add(mutableListOf(line))
                } else if (blocks.isNotEmpty() && line.startsWith(" ")) {
                    blocks.last().add(line)
                }
            }
            return blocks.map { it.joinToString("\n") }
        }

        fun summary(results: List<Result>): String {
            val passed = results.count { it.outcome is Outcome.Passed }
            val failed = results.count { it.outcome is Outcome.Failed || it.outcome is Outcome.Broken }
            val skipped = results.count { it.outcome is Outcome.Skipped }
            return "$passed passed, $failed failed, $skipped skipped"
        }

        /** `HH:MM:SS`. */
        fun timestamp(duration: Duration): String {
            val seconds = duration.inWholeSeconds
            return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        }
    }
}
