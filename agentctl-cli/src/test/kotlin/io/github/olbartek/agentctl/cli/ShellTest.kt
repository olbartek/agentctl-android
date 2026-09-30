package io.github.olbartek.agentctl.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What this guards: a command that never finishes (`adb shell` on a frozen emulator, say) cannot hang the CLI. Its
 * timeout applies even while it holds its output open, and it is killed when it runs out.
 */
class ShellTest {
    @Test
    fun captureReturnsWhatACommandPrinted() {
        assertEquals("hello\n", Shell.capture(listOf("echo", "hello")))
    }

    @Test
    fun captureGivesUpOnACommandThatDoesNotFinish() {
        val start = TimeSource.Monotonic.markNow()
        // Silent, with its output open, as `adb shell` is on a frozen device.
        assertNull(Shell.capture(listOf("sleep", "30"), timeoutSeconds = 1))
        assertTrue(start.elapsedNow() < 5.seconds, "took ${start.elapsedNow()}")
    }

    @Test
    fun aCommandThatRanOutOfTimeIsKilled() {
        val marker = "appctl-shell-test-${ProcessHandle.current().pid()}"
        // A sleep only this test starts: GNU and BSD sleep both take a fraction.
        val nap = "sleep 30.${ProcessHandle.current().pid()}"
        // The marker is in the shell's command line, and its sleep is its child: both must go. (No `exec -a`: dash,
        // Ubuntu's sh, has none.)
        assertNull(Shell.capture(listOf("sh", "-c", ": $marker; $nap; :"), timeoutSeconds = 1))
        val file = Files.createTempFile("capture", ".out").toFile()
        assertEquals(-1, Shell.captureTo(listOf("sh", "-c", ": $marker; $nap; :"), file, timeoutSeconds = 1))
        file.delete()
        Thread.sleep(200)
        val left = ProcessHandle.allProcesses().filter { it.info().commandLine().orElse("").let { line -> marker in line || nap in line } }.count()
        assertEquals(0, left, "a timed-out command is still running")
    }

    @Test
    fun aCommandThatIgnoresSigtermIsKilledAnyway() {
        val marker = "appctl-shell-stubborn-${ProcessHandle.current().pid()}"
        val start = TimeSource.Monotonic.markNow()
        assertNull(Shell.capture(listOf("sh", "-c", "trap '' TERM; : $marker; while :; do sleep 1; done"), timeoutSeconds = 1))
        assertTrue(start.elapsedNow() < 8.seconds, "took ${start.elapsedNow()}")
        Thread.sleep(200)
        val left = ProcessHandle.allProcesses().filter { it.info().commandLine().orElse("").contains(marker) }.count()
        assertEquals(0, left, "a command that ignored SIGTERM is still running")
    }

    @Test
    fun aLogSaysWhenItsCommandWasStopped() {
        val directory = Files.createTempDirectory("shell").toFile()
        val log = java.io.File(directory, "tool.log")
        assertEquals(-1, Shell.run(listOf("sh", "-c", "echo started; exec sleep 30"), directory, log, timeoutSeconds = 1))
        assertEquals("started\n\nagentctl: sh -c echo started; exec sleep 30 did not finish within 1 s; stopped it\n", log.readText())
    }
}
