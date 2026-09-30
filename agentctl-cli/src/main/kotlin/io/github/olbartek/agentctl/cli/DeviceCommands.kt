package io.github.olbartek.agentctl.cli

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * `app screenshot`, `app record start|stop`, `app statusbar clean|reset` and `app info`: the device itself, for demos
 * and screenshots. None of them needs the app's bridge.
 *
 * Each acts on `--device`, else the device the last launch recorded in `bridge.json` (when it was an Android one),
 * else the config's device, else the only connected one. None of them boots an emulator.
 */
internal class DeviceCommands(private val cli: Cli<*, *>, private val root: File) {
    private val layout = Layout(root, cli.config.outputPath)
    private val adb = AndroidSdk.adb(root, cli.environment)
    private val launcher = AppLauncher(cli, root)

    fun screenshot(path: String, device: String?): String {
        val target = target(device)
        val file = cli.resolve(path).absoluteFile.normalize()
        launcher.screenshot(target, file)
        return Message.saved(file, target)
    }

    fun recordStart(path: String, device: String?): String {
        RecordState.load(layout)?.let { if (isRunning(it)) throw AppCtlException(Message.recordingRunning(cli, it)) }
        val target = target(device)
        val file = cli.resolve(path).absoluteFile.normalize()
        file.parentFile?.mkdirs()
        val work = File(layout.output, "record").apply {
            deleteRecursively()
            mkdirs()
        }
        val log = File(layout.logs, "app-record.log").apply { parentFile.mkdirs() }
        File(work, STARTED_FILE).writeText(System.currentTimeMillis().toString())
        // A shell loop of screenrecord chunks, detached so it outlives this command, with the file in its command
        // line so `stop` can tell it from a process that has since taken its pid.
        val process = ProcessBuilder("nohup", "/bin/sh", "-c", RECORDER, "appctl-record", file.path, adb, target.serial, work.path)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(log))
            .start()
        val state = RecordState(target.serial, file.path, process.pid(), RecordState.PLATFORM, Instant.now())
        // Recorded before the wait, so an interrupted start still leaves a recorder `stop` can find.
        state.save(layout)
        if (!started(process, target)) {
            process.toHandle().destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            process.destroyForcibly()
            RecordState.file(layout).delete()
            throw AppCtlException("adb shell screenrecord did not start; log: ${log.path}")
        }
        return Message.recording(cli, file, target)
    }

    fun recordStop(): String {
        val state = RecordState.load(layout) ?: throw AppCtlException(Message.noRecording(cli))
        if (!isRunning(state)) {
            RecordState.file(layout).delete()
            throw AppCtlException(Message.recordingGone(state))
        }
        val recorder = ProcessHandle.of(state.pid).orElse(null)
        // The loop finishes its last chunk on SIGTERM (screenrecord finishes its file on SIGINT), then exits.
        recorder?.destroy()
        val deadline = TimeSource.Monotonic.markNow() + 30.seconds
        while (recorder?.isAlive == true && deadline.hasNotPassedNow()) Thread.sleep(100)
        // Still running: record.json stays, so another `stop` can try again.
        if (recorder?.isAlive == true) throw AppCtlException(Message.recorderDidNotFinish(cli, state))
        RecordState.file(layout).delete()
        val work = File(layout.output, "record")
        val stopped = System.currentTimeMillis()
        val startedAt = File(work, STARTED_FILE).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        val parts = pull(state.device, work)
        if (parts.isEmpty()) throw AppCtlException(Message.recordingNotWritten(cli, state))
        val file = File(state.file)
        val length = startedAt?.let { (stopped - it).milliseconds }
        try {
            Video.join(parts, file, work, length)
        } catch (error: IOException) {
            throw AppCtlException("the recording ${state.file} could not be saved: ${error.message}")
        }
        if (!file.isFile) throw AppCtlException(Message.recordingNotWritten(cli, state))
        return Message.recorded(file.path, (Video.duration(file) ?: length ?: Duration.ZERO).inWholeMilliseconds / 1000.0)
    }

    fun statusbar(clean: Boolean, device: String?): String {
        val target = target(device)
        val log = File(layout.logs, "app-statusbar.log")
        val saved = File(layout.output, "statusbar-${target.serial}.txt")
        if (clean) {
            // Demo mode must be allowed first; what it was is kept, so `reset` can put it back.
            if (!saved.isFile) {
                val allowed = Shell.capture(listOf(adb, "-s", target.serial, "shell", "settings", "get", "global", StatusBar.ALLOWED), timeoutSeconds = 60)?.trim()
                saved.parentFile?.mkdirs()
                saved.writeText(allowed ?: "null")
            }
        }
        for (arguments in if (clean) StatusBar.clean else StatusBar.reset(saved.takeIf { it.isFile }?.readText()?.trim())) {
            val status = Shell.run(listOf(adb, "-s", target.serial, "shell") + arguments, root, log, append = true, timeoutSeconds = 60)
            if (status != 0) throw AppCtlException("adb shell ${arguments.take(2).joinToString(" ")} failed; log: ${log.path}")
        }
        if (!clean) saved.delete()
        return Message.statusBar(clean, target)
    }

    fun info(device: String?): String {
        val target = target(device)
        val appId = cli.config.applicationId
        val path = Shell.capture(listOf(adb, "-s", target.serial, "shell", "pm", "path", appId), timeoutSeconds = 60)
        if (path.isNullOrBlank()) throw AppCtlException(Message.notInstalled(cli, target))
        val dump = Shell.capture(listOf(adb, "-s", target.serial, "shell", "dumpsys", "package", appId), timeoutSeconds = 60) ?: ""
        val (version, build) = AppInfo.versions(dump)
        return AppInfo(appId, build, target.serial, RecordState.PLATFORM, version).render()
    }

    /** `--device`, else the last launch's device when it was an Android one, else the config's, else the only one. */
    fun target(device: String?): Device {
        if (device != null) return launcher.find(device)
        val recorded = try {
            BridgeState.load(layout)
        } catch (error: Unreadable) {
            throw AppCtlException(Message.unreadableBridgeState(cli, error.reason))
        }
        if (recorded != null && recorded.platform == BridgeState.PLATFORM) return launcher.find(recorded.device)
        return launcher.find(cli.config.device)
    }

    /** Whether the recorder is still running, and is ours: alive, and its command line names the file. */
    private fun isRunning(state: RecordState): Boolean {
        if (state.pid <= 0 || ProcessHandle.of(state.pid).map { it.isAlive }.orElse(false) != true) return false
        val command = Shell.capture(listOf("ps", "-o", "command=", "-p", state.pid.toString()), timeoutSeconds = 10) ?: return false
        return state.file in command
    }

    /** Whether the recorder got screenrecord going on the device within a few seconds. */
    private fun started(process: Process, device: Device): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + 5.seconds
        while (deadline.hasNotPassedNow()) {
            if (!process.isAlive) return false
            val pid = Shell.capture(listOf(adb, "-s", device.serial, "shell", "pidof", "screenrecord"), timeoutSeconds = 10)
            if (!pid.isNullOrBlank()) return true
            Thread.sleep(250)
        }
        return false
    }

    /** The chunks the recorder wrote on the device, pulled into [work] and removed there. */
    private fun pull(serial: String, work: File): List<File> {
        val chunks = File(work, CHUNKS_FILE).takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val log = File(layout.logs, "app-record.log")
        return chunks.mapIndexed { index, remote ->
            val local = File(work, "part-$index.mp4")
            Shell.run(listOf(adb, "-s", serial, "pull", remote, local.path), work, log, append = true, timeoutSeconds = 300)
            Shell.run(listOf(adb, "-s", serial, "shell", "rm", "-f", remote), work, log, append = true, timeoutSeconds = 60)
            local
        }.filter { it.isFile && it.length() > 0 }
    }

    companion object {
        private const val STARTED_FILE = "started-ms"
        private const val CHUNKS_FILE = "chunks.txt"

        /**
         * The recorder: `adb shell screenrecord` in back-to-back chunks under its three-minute limit, until SIGTERM or
         * SIGINT, when it stops the chunk being written (screenrecord finishes its file on SIGINT) and exits. Each
         * chunk's device path goes to `chunks.txt`. A chunk that fails at once ends the loop rather than spinning.
         * Arguments: the output file (only so `ps` shows it), adb, the serial, the work directory.
         */
        private val RECORDER = """
            adb=${'$'}2; serial=${'$'}3; work=${'$'}4
            stop=0; i=0
            trap 'stop=1; "${'$'}adb" -s "${'$'}serial" shell pkill -2 screenrecord' INT TERM
            while [ ${'$'}stop = 0 ]; do
              remote=/sdcard/appctl-record-${'$'}i.mp4
              echo "${'$'}remote" >> "${'$'}work/$CHUNKS_FILE"
              began=${'$'}(date +%s)
              "${'$'}adb" -s "${'$'}serial" shell screenrecord --time-limit ${AppTest.Recording.CHUNK_SECONDS} "${'$'}remote" &
              chunk=${'$'}!
              wait ${'$'}chunk; status=${'$'}?
              # Interrupted by the trap: wait again, for the chunk to finish its file.
              if [ ${'$'}stop = 1 ]; then wait ${'$'}chunk; break; fi
              if [ ${'$'}status != 0 ] && [ ${'$'}((${'$'}(date +%s) - began)) -lt 2 ]; then
                echo "screenrecord failed (exit ${'$'}status)"; exit 1
              fi
              i=${'$'}((i + 1))
            done
        """.trimIndent()
    }
}

/** `<outputPath>/record.json`: the recorder `app record start` left running, for `app record stop`. As `bridge.json`. */
internal data class RecordState(
    /** The `adb` serial. */
    val device: String,
    /** The absolute path of the .mp4. */
    val file: String,
    val pid: Long,
    val platform: String,
    val startedAt: Instant,
) {
    fun render(): String = FlatJson.pretty(
        mapOf(
            "device" to FlatJson.quote(device),
            "file" to FlatJson.quote(file),
            "pid" to pid.toString(),
            "platform" to FlatJson.quote(platform),
            "startedAt" to FlatJson.quote(startedAt.truncatedTo(ChronoUnit.SECONDS).toString()),
        ),
    )

    fun save(layout: Layout) {
        val file = file(layout)
        file.parentFile?.mkdirs()
        file.writeText(render())
    }

    companion object {
        const val FILE_NAME: String = "record.json"
        const val PLATFORM: String = BridgeState.PLATFORM

        fun file(layout: Layout): File = File(layout.output, FILE_NAME)

        /** The saved state, `null` when there is none; a file that is not one is an error (exit 3). */
        fun load(layout: Layout): RecordState? {
            val file = file(layout)
            if (!file.exists()) return null
            val text = try {
                file.readText()
            } catch (error: IOException) {
                throw AppCtlException("cannot read ${layout.output.name}/$FILE_NAME: $error")
            }
            return parse(text) ?: throw AppCtlException("cannot read ${layout.output.name}/$FILE_NAME: not a recording state")
        }

        fun parse(text: String): RecordState? {
            val fields = FlatJson.parse(text) ?: return null
            val startedAt = (fields["startedAt"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            return RecordState(
                device = fields["device"] as? String ?: return null,
                file = fields["file"] as? String ?: return null,
                pid = fields["pid"] as? Long ?: return null,
                platform = fields["platform"] as? String ?: return null,
                startedAt = startedAt,
            )
        }
    }
}

/** `app info`'s line: compact JSON with sorted keys, as agentctl-ios prints it. */
internal data class AppInfo(val appId: String, val build: String, val device: String, val platform: String, val version: String) {
    fun render(): String = FlatJson.compact(
        mapOf(
            "appId" to FlatJson.quote(appId),
            "build" to FlatJson.quote(build),
            "device" to FlatJson.quote(device),
            "platform" to FlatJson.quote(platform),
            "version" to FlatJson.quote(version),
        ),
    )

    companion object {
        /** `versionName` and `versionCode` from `dumpsys package` (the first of each, the installed package's). */
        fun versions(dump: String): Pair<String, String> {
            val name = Regex("versionName=(\\S*)").find(dump)?.groupValues?.get(1) ?: ""
            val code = Regex("versionCode=(\\d+)").find(dump)?.groupValues?.get(1) ?: ""
            return name to code
        }
    }
}

/**
 * The clean status bar, through SystemUI's demo mode: 09:41, full Wi-Fi and mobile signal, a full battery that is
 * not charging, no notification icons. As the reference's `simctl status_bar override`.
 */
internal object StatusBar {
    const val ALLOWED: String = "sysui_demo_allowed"
    private const val DEMO = "com.android.systemui.demo"

    private fun demo(command: String, vararg extras: String): List<String> =
        listOf("am", "broadcast", "-a", DEMO, "-e", "command", command) + extras

    val clean: List<List<String>> = listOf(
        listOf("settings", "put", "global", ALLOWED, "1"),
        demo("enter"),
        demo("clock", "-e", "hhmm", "0941"),
        demo("network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true"),
        demo("network", "-e", "mobile", "show", "-e", "datatype", "none", "-e", "level", "4", "-e", "fully", "true"),
        demo("battery", "-e", "level", "100", "-e", "plugged", "false"),
        demo("notifications", "-e", "visible", "false"),
    )

    /** Out of demo mode, and demo mode allowed again only if it was before `clean` ([allowed]: what it was). */
    fun reset(allowed: String?): List<List<String>> = listOf(demo("exit")) + when (allowed) {
        null -> emptyList()
        "null", "" -> listOf(listOf("settings", "delete", "global", ALLOWED))
        else -> listOf(listOf("settings", "put", "global", ALLOWED, allowed))
    }
}

/** Joining a recording's chunks, and holding its last frame until the recording ended. */
internal object Video {
    /**
     * [parts] as one video at [video]. `screenrecord` writes a frame only when the screen changes, so a recording
     * whose screen was still at the end stops short of when it was stopped; when [length] is known and `ffmpeg` is
     * installed, the last frame is held until then. Without `ffmpeg`, a single part is copied as it is and several
     * are kept as numbered parts beside [video].
     */
    fun join(parts: List<File>, video: File, work: File, length: Duration?): List<File> {
        video.absoluteFile.parentFile?.mkdirs()
        val joined = if (parts.size == 1) {
            parts[0]
        } else {
            val list = File(work, "parts.txt").apply { writeText(parts.joinToString("\n") { "file '${it.absolutePath}'" } + "\n") }
            val out = File(work, "joined.mp4")
            val status = ffmpeg(listOf("-f", "concat", "-safe", "0", "-i", list.path, "-c", "copy", out.path), work)
            if (status != 0) {
                return parts.mapIndexed { index, part ->
                    part.copyTo(File(video.parentFile, "${video.nameWithoutExtension}.part${index + 1}.mp4"), overwrite = true)
                }
            }
            out
        }
        val hold = length?.let { total -> duration(joined)?.let { total - it } }
        if (hold != null && hold > 0.2.seconds) {
            val seconds = String.format(java.util.Locale.ROOT, "%.3f", hold.inWholeMilliseconds / 1000.0)
            val padded = File(work, "held.mp4")
            val status = ffmpeg(
                listOf(
                    "-i", joined.path, "-vf", "tpad=stop_mode=clone:stop_duration=$seconds",
                    "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", padded.path,
                ),
                work,
            )
            if (status == 0) return listOf(padded.copyTo(video, overwrite = true))
        }
        return listOf(joined.copyTo(video, overwrite = true))
    }

    /** A video's length, from `ffprobe`; `null` without it. */
    fun duration(video: File): Duration? =
        Shell.capture(listOf("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", video.path), timeoutSeconds = 60)
            ?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong().milliseconds }

    private fun ffmpeg(arguments: List<String>, work: File): Int =
        Shell.run(listOf("ffmpeg", "-y", "-loglevel", "error") + arguments, work, File(work, "ffmpeg.log"), append = true, timeoutSeconds = 600)
}
