package io.github.olbartek.agentctl.cli

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A recording of a device's screen: a local shell loop of `adb shell screenrecord` chunks, each under screenrecord's
 * three-minute limit, which `app record` and `app test --record` both use.
 *
 * Each recorder has its own work directory (`<root>/<its pid>`) and its own device paths
 * (`/sdcard/appctl-record-<its pid>-<n>.mp4`), and stops only its own chunk, by the device pid the chunk reported,
 * so two recorders on one device (or another tool's screenrecord) never interfere.
 */
internal class ScreenRecorder private constructor(
    private val adb: String,
    private val serial: String,
    /** The loop's pid, which `record.json` keeps. */
    val pid: Long,
    root: File,
    private val process: Process?,
) {
    /** The chunks' list, the current chunk's device pid, the start time, the pulled parts. */
    val work: File = File(root, pid.toString())

    private val handle: ProcessHandle? get() = process?.toHandle() ?: ProcessHandle.of(pid).orElse(null)

    /**
     * Waits until the first chunk is recording on the device (it reported its device pid), and notes the time it
     * started. `false` if the loop ended first or [timeout] passed.
     */
    fun awaitStart(timeout: Duration = 20.seconds): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (deadline.hasNotPassedNow()) {
            if (handle?.isAlive != true) return false
            if (!File(work, CHUNK_PID).readTextOrNull()?.trim().isNullOrEmpty()) {
                File(work, STARTED).writeText(System.currentTimeMillis().toString())
                // Its pid is out as its shell becomes screenrecord: give it a moment to be recording.
                Thread.sleep(500)
                return handle?.isAlive == true
            }
            Thread.sleep(100)
        }
        return false
    }

    /** Stops the loop, which finishes its chunk first; `false` if it is still running after [timeout]. */
    fun stop(timeout: Duration): Boolean {
        val handle = handle ?: return true
        handle.destroy()
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (handle.isAlive && deadline.hasNotPassedNow()) Thread.sleep(100)
        return !handle.isAlive
    }

    /** After a start that failed: the loop and what it started are stopped, and its chunks removed from the device. */
    fun abandon(log: File) {
        stop(5.seconds)
        handle?.let { loop ->
            (loop.descendants().toList() + loop).filter { it.isAlive }.forEach { it.destroyForcibly() }
        }
        chunks().forEach { remote ->
            Shell.run(listOf(adb, "-s", serial, "shell", "rm", "-f", remote), work, log, append = true, timeoutSeconds = 60)
        }
        work.deleteRecursively()
    }

    /**
     * Pulls the chunks the loop wrote and joins them into [video], holding the last frame until [stoppedAt] (epoch ms;
     * `null`: the recording's own length). The files written, or `null` when nothing was recorded. The work directory
     * is removed once they are.
     */
    fun finish(video: File, log: File, stoppedAt: Long? = null): List<File>? {
        if (!work.isDirectory) return null
        val parts = chunks().mapIndexedNotNull { index, remote ->
            val local = File(work, "part-$index.mp4")
            val pulled = Shell.run(listOf(adb, "-s", serial, "pull", remote, local.path), work, log, append = true, timeoutSeconds = 300) == 0
            // Removed from the device only once it is here: a pull that failed leaves the only copy where it is.
            if (pulled) Shell.run(listOf(adb, "-s", serial, "shell", "rm", "-f", remote), work, log, append = true, timeoutSeconds = 60)
            local.takeIf { it.isFile && it.length() > 0 }
        }
        if (parts.isEmpty()) return null
        val started = File(work, STARTED).readTextOrNull()?.trim()?.toLongOrNull()
        val length = if (started != null && stoppedAt != null) (stoppedAt - started).milliseconds else null
        val files = try {
            Video.join(parts, video, work, length)
        } catch (error: IOException) {
            throw AppCtlException("the recording ${video.path} could not be saved: ${error.message}")
        }
        if (files.all { it.isFile }) work.deleteRecursively()
        return files
    }

    private fun chunks(): List<String> =
        File(work, CHUNKS).readTextOrNull()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    companion object {
        /** `screenrecord`'s own limit is 180 seconds. */
        const val CHUNK_SECONDS: Int = 180

        private const val CHUNKS = "chunks.txt"
        private const val CHUNK_PID = "chunk.pid"
        private const val STARTED = "started-ms"

        /**
         * Starts a recorder for [file] (named in its command line only, so `ps` shows whose it is), its work under
         * [root]. [detached]: it outlives this process (`app record start`), where `app test` keeps it as a child.
         */
        fun start(
            adb: String,
            serial: String,
            file: File,
            root: File,
            log: File,
            detached: Boolean,
            chunkSeconds: Int = CHUNK_SECONDS,
        ): ScreenRecorder {
            root.mkdirs()
            log.parentFile?.mkdirs()
            val command = (if (detached) listOf("nohup") else emptyList()) +
                listOf("/bin/sh", "-c", SCRIPT, "appctl-record", file.path, adb, serial, root.path, chunkSeconds.toString())
            val process = ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .start()
            return ScreenRecorder(adb, serial, process.pid(), root, process)
        }

        /** The recorder a previous command started: [pid] from `record.json`. */
        fun of(adb: String, serial: String, pid: Long, root: File): ScreenRecorder = ScreenRecorder(adb, serial, pid, root, null)

        /**
         * The loop. Arguments: the output file (for `ps` only), adb, the serial, the work root, the chunk limit. Each
         * chunk's device shell prints its pid and becomes screenrecord (`exec`), so the pid is screenrecord's. On
         * SIGTERM or SIGINT the loop sends that pid SIGINT (screenrecord finishes its file on it), waiting for the pid
         * if the chunk is just starting and giving a new chunk a second to set up, then waits for the chunk and exits. A chunk that fails early ends the loop.
         */
        private val SCRIPT = """
            adb=${'$'}2; serial=${'$'}3; work=${'$'}4/${'$'}${'$'}; limit=${'$'}5
            mkdir -p "${'$'}work"
            stop=0; i=0
            trap 'stop=1' INT TERM
            while [ ${'$'}stop = 0 ]; do
              remote=/sdcard/appctl-record-${'$'}${'$'}-${'$'}i.mp4
              echo "${'$'}remote" >> "${'$'}work/$CHUNKS"
              : > "${'$'}work/$CHUNK_PID"
              began=${'$'}(date +%s)
              "${'$'}adb" -s "${'$'}serial" shell "echo \${'$'}\${'$'} && exec screenrecord --time-limit ${'$'}limit ${'$'}remote" > "${'$'}work/$CHUNK_PID" &
              chunk=${'$'}!
              status=0; interrupted=0; tries=0
              while :; do
                if [ ${'$'}stop = 0 ]; then
                  # Returns when the chunk ends, or early when a signal arrives (then stop is 1).
                  wait ${'$'}chunk; status=${'$'}?
                  [ ${'$'}stop = 0 ] && break
                  continue
                fi
                device=${'$'}(head -n 1 "${'$'}work/$CHUNK_PID" 2>/dev/null | tr -d '\r')
                if [ -n "${'$'}device" ]; then
                  # A chunk gets a second first: screenrecord only finishes its file on SIGINT once it is set up.
                  while [ ${'$'}interrupted = 0 ] && [ ${'$'}((${'$'}(date +%s) - began)) -lt 2 ]; do sleep 0.2; done
                  [ ${'$'}interrupted = 0 ] && "${'$'}adb" -s "${'$'}serial" shell kill -2 "${'$'}device"
                  interrupted=1
                  wait ${'$'}chunk
                  kill -0 ${'$'}chunk 2>/dev/null || break
                else
                  # Just starting: its pid is on the way. A chunk that never reports one is waited out.
                  tries=${'$'}((tries + 1))
                  if [ ${'$'}tries -gt 100 ]; then wait ${'$'}chunk; break; fi
                  sleep 0.1
                fi
              done
              [ ${'$'}stop = 1 ] && break
              if [ ${'$'}status != 0 ] && [ ${'$'}((${'$'}(date +%s) - began)) -lt ${'$'}((limit / 2)) ]; then
                echo "screenrecord failed (exit ${'$'}status)"; exit 1
              fi
              i=${'$'}((i + 1))
            done
        """.trimIndent()

        private fun File.readTextOrNull(): String? = try {
            if (isFile) readText() else null
        } catch (_: IOException) {
            null
        }
    }
}
