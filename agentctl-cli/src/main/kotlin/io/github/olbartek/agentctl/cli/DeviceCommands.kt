package io.github.olbartek.agentctl.cli

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        val target = target(device)
        RecordState.load(cli, layout)?.let { if (isRunning(it)) throw AppCtlException(Message.recordingRunning(cli, it)) }
        val file = cli.resolve(path).absoluteFile.normalize()
        file.parentFile?.mkdirs()
        val log = File(layout.logs, "app-record.log").apply { parentFile.mkdirs() }
        val recorder = ScreenRecorder.start(adb, target.serial, file, workRoot, log, detached = true)
        // Recorded before the wait, so an interrupted start still leaves a recorder `stop` can find.
        RecordState(target.serial, file.path, recorder.pid, RecordState.PLATFORM, Instant.now()).save(layout)
        if (!recorder.awaitStart()) {
            recorder.abandon(log)
            RecordState.file(layout).delete()
            throw AppCtlException("adb shell screenrecord did not start; log: ${log.path}")
        }
        return Message.recording(cli, file, target)
    }

    fun recordStop(): String {
        val state = RecordState.load(cli, layout) ?: throw AppCtlException(Message.noRecording(cli))
        val recorder = ScreenRecorder.of(adb, state.device, state.pid, workRoot)
        val log = File(layout.logs, "app-record.log")
        if (!isRunning(state)) {
            RecordState.file(layout).delete()
            // What a recorder that died had finished is still on the device: it is saved, not lost.
            val saved = recorder.finish(File(state.file), log)
            throw AppCtlException(Message.recordingGone(state) + (saved?.let { "; saved what it recorded to ${it.joinToString(", ") { f -> f.path }}" } ?: ""))
        }
        // Timed here, not after the recorder has wound down: the hold runs until the stop was asked for.
        val stoppedAt = System.currentTimeMillis()
        // Still running after 30 s: record.json stays, so another `stop` can try again.
        if (!recorder.stop(30.seconds)) throw AppCtlException(Message.recorderDidNotFinish(cli, state))
        RecordState.file(layout).delete()
        val files = recorder.finish(File(state.file), log, stoppedAt) ?: throw AppCtlException(Message.recordingNotWritten(cli, state))
        val seconds = files.mapNotNull(Video::duration).fold(Duration.ZERO, Duration::plus).inWholeMilliseconds / 1000.0
        return Message.recorded(files.joinToString(", ") { it.path }, seconds)
    }

    fun statusbar(clean: Boolean, device: String?): String {
        val target = target(device)
        val log = File(layout.logs, "app-statusbar.log")
        // Keyed by the AVD as well: an emulator's serial is reused by the next one started.
        val saved = File(layout.output, "statusbar-${target.serial}-${target.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.txt")
        if (clean && !saved.isFile) {
            // Demo mode must be allowed first; what it was is kept, so `reset` can put it back.
            val allowed = Shell.capture(listOf(adb, "-s", target.serial, "shell", "settings", "get", "global", StatusBar.ALLOWED), timeoutSeconds = 60)
                ?: throw AppCtlException("adb shell settings get did not answer; log: ${log.path}")
            saved.parentFile?.mkdirs()
            saved.writeText(allowed.trim())
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
            ?: throw AppCtlException("adb shell pm path did not answer on ${target.label} [${target.serial}]")
        if (path.isBlank()) throw AppCtlException(Message.notInstalled(cli, target))
        val dump = Shell.capture(listOf(adb, "-s", target.serial, "shell", "dumpsys", "package", appId), timeoutSeconds = 60)
            ?: throw AppCtlException("adb shell dumpsys package did not answer on ${target.label} [${target.serial}]")
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
        if (recorded != null && recorded.platform == BridgeState.PLATFORM) {
            // Unless it has gone since: then the config's, as if there were no launch state.
            if (launcher.isConnected(recorded.device)) return launcher.find(recorded.device)
        }
        return launcher.find(cli.config.device)
    }

    /** Whether the recorder is still running, and is ours: alive, and its command line names the file. */
    private fun isRunning(state: RecordState): Boolean {
        if (state.pid <= 0 || ProcessHandle.of(state.pid).map { it.isAlive }.orElse(false) != true) return false
        val command = Shell.capture(listOf("ps", "-o", "command=", "-p", state.pid.toString()), timeoutSeconds = 10) ?: return false
        return state.file in command
    }

    /** Where each recording's chunks and bookkeeping go, one directory per recorder. */
    private val workRoot = File(layout.output, "record")
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
        // Whole or not at all, as bridge.json.
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(render())
        if (!temporary.renameTo(file)) {
            file.writeText(render())
            temporary.delete()
        }
    }

    companion object {
        const val FILE_NAME: String = "record.json"
        const val PLATFORM: String = BridgeState.PLATFORM

        fun file(layout: Layout): File = File(layout.output, FILE_NAME)

        /** The saved state, `null` when there is none; a file that is not one is an error (exit 3). */
        fun load(cli: Cli<*, *>, layout: Layout): RecordState? {
            val file = file(layout)
            if (!file.exists()) return null
            val name = "${cli.config.outputPath}/$FILE_NAME"
            val text = try {
                file.readText()
            } catch (error: IOException) {
                throw AppCtlException("cannot read $name: $error")
            }
            return parse(text)
                ?: throw AppCtlException("cannot read $name: not a recording state (expected device, file, pid, platform and startedAt)")
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
            val name = Regex("versionName=([^\\r\\n]*)").find(dump)?.groupValues?.get(1)?.trim() ?: ""
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
        // An older file there must not pass for this recording if this one ends up in parts.
        video.delete()
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
