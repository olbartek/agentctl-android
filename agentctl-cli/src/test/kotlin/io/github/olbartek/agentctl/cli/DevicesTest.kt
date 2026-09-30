package io.github.olbartek.agentctl.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What this guards: a frozen emulator, which `adb devices` still lists as `device` while its shell never answers,
 * is skipped with a warning instead of hanging every command that lists devices. The `adb` here is a fake that
 * never exits for the frozen one.
 */
class DevicesTest {
    private val adb = Files.createTempFile("adb", "").toFile().apply {
        writeText(
            """
            #!/bin/sh
            # A fake adb: emulator-5554 answers, emulator-5556 is frozen (its shell never exits).
            case "${'$'}*" in
              devices) printf 'List of devices attached\nemulator-5556\tdevice\nemulator-5554\tdevice\n' ;;
              "-s emulator-5556 shell"*) exec sleep 600 ;;
              "-s emulator-5554 shell getprop ro.build.version.release") echo 16 ;;
              "-s emulator-5554 emu avd name") printf 'nzoz_pixel7_api36\nOK\n' ;;
              *) exit 1 ;;
            esac
            """.trimIndent() + "\n",
        )
        setExecutable(true)
    }

    @Test
    fun aDeviceThatDoesNotAnswerIsSkippedNotWaitedFor() {
        val warnings = mutableListOf<String>()
        val start = TimeSource.Monotonic.markNow()
        val devices = Devices.connected(adb.path, probeSeconds = 2) { warnings.add(it) }
        assertTrue(start.elapsedNow() < 8.seconds, "took ${start.elapsedNow()}")
        assertEquals(listOf(Device("emulator-5554", "nzoz_pixel7_api36", "16")), devices)
        assertEquals(listOf("emulator-5556 does not answer (adb shell timed out after 2 s); skipping it"), warnings)
        // Nothing the frozen device's probe started is left running.
        Thread.sleep(200)
        val left = ProcessHandle.allProcesses().filter { it.info().commandLine().orElse("").contains("sleep 600") }.count()
        assertEquals(0, left)
    }

    @Test
    fun aStuckAdbServerSaysSo() {
        assertEquals(
            "adb devices did not answer within 60 s; the adb server may be stuck: run 'adb kill-server', or restart the emulator",
            Message.adbDidNotAnswer(Devices.LIST_SECONDS),
        )
    }
}
