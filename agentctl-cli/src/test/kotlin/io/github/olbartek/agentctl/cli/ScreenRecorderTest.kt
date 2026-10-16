package io.github.olbartek.agentctl.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What this guards: the recorder loop behind `app record` and `app test --record`, against a fake `adb` whose
 * "device" is a directory here and whose screenrecord finishes its file only on SIGINT, as the real one does. A stop
 * finishes the chunk being written, even one that is just starting; two recorders never touch each other's chunks.
 */
class ScreenRecorderTest {
    private val sandbox = Files.createTempDirectory("recorder").toFile()
    private val device = File(sandbox, "device").apply { mkdirs() }
    private val bin = File(sandbox, "bin").apply { mkdirs() }
    private val log = File(sandbox, "record.log")

    /**
     * A fake adb: `shell` runs the command here, with /sdcard mapped into [device] and signals as a device has them.
     *
     * Its screenrecord reports its pid (which the recorder then signals) only once its SIGINT trap is set, as the
     * real one is set up by the time the recorder signals it; the chunk's own `echo $$` is dropped for that. It waits
     * in `wait`, never on a foreground child: macOS's /bin/sh (bash 3.2) drops a SIGINT that lands as a foreground
     * child exits, and the chunk would run to its limit. Either was a stop that left no finished chunk (#81).
     */
    private fun adb(startDelay: String = "0", failsAtOnce: Boolean = false, writesNothing: Boolean = false): String {
        File(bin, "screenrecord").apply {
            writeText(
                """
                #!/bin/sh
                # screenrecord --time-limit N FILE: "partial" until it ends, "complete" once it does, on SIGINT too.
                ${if (failsAtOnce) "echo ${'$'}${'$'}; echo 'cannot record' >&2; exit 0" else ""}
                ${if (writesNothing) "file=${'$'}3; trap ': >> \"${'$'}file\"; exit 0' INT; : > \"${'$'}file\"; echo ${'$'}${'$'}; while :; do sleep 0.1 & wait ${'$'}!; done" else ""}
                limit=${'$'}2; file=${'$'}3
                trap 'echo complete > "${'$'}file"; exit 0' INT
                echo partial > "${'$'}file"; echo ${'$'}${'$'}
                n=0; while [ ${'$'}n -lt ${'$'}((limit * 10)) ]; do sleep 0.1 & wait ${'$'}!; n=${'$'}((n + 1)); done
                echo complete > "${'$'}file"
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        return File(bin, "adb").apply {
            writeText(
                """
                #!/bin/sh
                shift 2  # -s <serial>
                what=${'$'}1; shift
                case "${'$'}what" in
                  shell)
                    command=${'$'}(printf '%s ' "${'$'}@" | sed -e "s#/sdcard/#${device.path}/#g" -e 's#echo [$][$] && exec screenrecord#exec screenrecord#')
                    sleep $startDelay
                    # A device's processes have SIGINT as usual, not ignored as a background job's here would be.
                    PATH="${bin.path}:${'$'}PATH" exec perl -e '${'$'}SIG{INT}="DEFAULT"; exec @ARGV' sh -c "${'$'}command" ;;
                  pull) cp "${'$'}(echo "${'$'}1" | sed "s#/sdcard/#${device.path}/#")" "${'$'}2" ;;
                  *) exit 1 ;;
                esac
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }.path
    }

    @AfterTest
    fun cleanUp() {
        ProcessHandle.allProcesses().filter { sandbox.path in it.info().commandLine().orElse("") }.forEach { it.destroyForcibly() }
        sandbox.deleteRecursively()
    }

    private fun chunkFiles(): Map<String, String> = device.listFiles().orEmpty().filter { it.name.endsWith(".mp4") }.associate { it.name to it.readText().trim() }

    /** A recording whose file is empty is not a recording: `app test --record` then says nothing was recorded. */
    @Test
    fun anEmptyRecordingIsNotReportedAsOne() {
        val recorder = ScreenRecorder.start(adb(writesNothing = true), "emulator-5556", File(sandbox, "demo.mp4"), File(sandbox, "work"), log, detached = false, chunkSeconds = 30)
        assertTrue(recorder.awaitStart(10.seconds), log.readText())
        assertTrue(recorder.stop(10.seconds))
        assertEquals(listOf(""), chunkFiles().values.toList())
        assertEquals(null, recorder.finish(File(sandbox, "out/demo.mp4"), log))
        assertTrue(!File(sandbox, "out/demo.mp4").exists())
    }

    @Test
    fun aStopFinishesTheChunkBeingWritten() {
        val recorder = ScreenRecorder.start(adb(), "emulator-5556", File(sandbox, "demo.mp4"), File(sandbox, "work"), log, detached = false, chunkSeconds = 30)
        assertTrue(recorder.awaitStart(10.seconds), log.readText())
        // Recording, not merely started: on a loaded machine the fake takes a while to open its file.
        val recording = TimeSource.Monotonic.markNow()
        while (chunkFiles().isEmpty() && recording.elapsedNow() < 10.seconds) Thread.sleep(50)
        val start = TimeSource.Monotonic.markNow()
        assertTrue(recorder.stop(10.seconds))
        assertTrue(start.elapsedNow() < 5.seconds, "took ${start.elapsedNow()}")
        assertEquals(listOf("complete"), chunkFiles().values.toList())
    }

    /** A stop that lands while a chunk is starting, before it reported its pid, still stops that chunk. */
    @Test
    fun aStopWhileAChunkIsStartingStillStopsIt() {
        val recorder = ScreenRecorder.start(adb(startDelay = "0.5"), "emulator-5556", File(sandbox, "demo.mp4"), File(sandbox, "work"), log, detached = false, chunkSeconds = 30)
        // The loop has begun its first chunk, whose device has not answered with a pid yet.
        val deadline = TimeSource.Monotonic.markNow() + 5.seconds
        while (!File(recorder.work, "chunks.txt").isFile && deadline.hasNotPassedNow()) Thread.sleep(20)
        val start = TimeSource.Monotonic.markNow()
        assertTrue(recorder.stop(10.seconds), "the recorder waited the chunk out")
        assertTrue(start.elapsedNow() < 5.seconds, "took ${start.elapsedNow()}")
        assertEquals(listOf("complete"), chunkFiles().values.toList())
    }

    @Test
    fun twoRecordersKeepToTheirOwnChunks() {
        val first = ScreenRecorder.start(adb(), "emulator-5556", File(sandbox, "a.mp4"), File(sandbox, "work"), log, detached = false, chunkSeconds = 30)
        val second = ScreenRecorder.start(adb(), "emulator-5556", File(sandbox, "b.mp4"), File(sandbox, "work"), log, detached = false, chunkSeconds = 30)
        assertTrue(first.awaitStart(10.seconds) && second.awaitStart(10.seconds), log.readText())
        assertTrue(first.stop(10.seconds))
        val chunks = chunkFiles()
        assertEquals("complete", chunks["appctl-record-${first.pid}-0.mp4"])
        assertEquals("partial", chunks["appctl-record-${second.pid}-0.mp4"], "the second recorder's chunk was stopped too")
        assertTrue(second.stop(10.seconds))
        assertEquals("complete", chunkFiles()["appctl-record-${second.pid}-0.mp4"])
    }

    /**
     * A stop that lands after the loop checked its flag but before it began to wait for the chunk only sets the flag,
     * and the loop then waits: the stop signals it again, so it does not wait the chunk out (#81). The stand-in loop
     * here always takes the first signal before its wait.
     */
    @Test
    fun aStopThatLandsBeforeTheLoopWaitsIsSentAgain() {
        val ready = File(sandbox, "ready")
        val script = "trap 'stop=1' TERM; stop=0; : > \"${'$'}1\"; while [ ${'$'}stop = 0 ]; do :; done; " +
            "sleep 30 & chunk=${'$'}!; wait ${'$'}chunk; kill ${'$'}chunk"
        val loop = ProcessBuilder("/bin/sh", "-c", script, "appctl-record", ready.path)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start()
        val deadline = TimeSource.Monotonic.markNow() + 5.seconds
        while (!ready.isFile && deadline.hasNotPassedNow()) Thread.sleep(20)
        val start = TimeSource.Monotonic.markNow()
        assertTrue(ScreenRecorder.of("adb", "emulator-5556", loop.pid(), File(sandbox, "work")).stop(10.seconds), "the loop waited its chunk out")
        assertTrue(start.elapsedNow() < 5.seconds, "took ${start.elapsedNow()}")
    }

    /** Chunks that reach their limit are followed by the next, and all end up finished. */
    @Test
    fun chunksFollowEachOtherAtTheirLimit() {
        val recorder = ScreenRecorder.start(adb(), "emulator-5556", File(sandbox, "demo.mp4"), File(sandbox, "work"), log, detached = false, chunkSeconds = 2)
        assertTrue(recorder.awaitStart(10.seconds), log.readText())
        Thread.sleep(3500)
        assertTrue(recorder.stop(10.seconds))
        val chunks = chunkFiles()
        assertTrue(chunks.size >= 2, "chunks: $chunks")
        assertEquals(setOf("complete"), chunks.values.toSet())
    }

    /** A screenrecord that cannot record ends at once, whatever its exit status: the loop gives up, not spins. */
    @Test
    fun aRecorderThatCannotRecordGivesUp() {
        val recorder = ScreenRecorder.start(adb(failsAtOnce = true), "emulator-5556", File(sandbox, "demo.mp4"), File(sandbox, "work"), log, detached = false, chunkSeconds = 30)
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (recorder.isAlive && deadline.hasNotPassedNow()) Thread.sleep(50)
        assertTrue(!recorder.isAlive, "the loop is still running")
        assertTrue("screenrecord ended early" in log.readText(), log.readText())
        assertTrue(File(recorder.work, "chunks.txt").readLines().size <= 1)
    }
}
