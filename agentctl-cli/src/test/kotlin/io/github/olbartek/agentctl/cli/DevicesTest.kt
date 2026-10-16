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
 * What this guards: a frozen emulator, which `adb devices` still lists as `device` while its shell never answers,
 * is skipped with a warning instead of hanging every command that lists devices. The `adb` here is a fake that
 * never exits for the frozen one.
 */
class DevicesTest {
    /** A sleep only this test starts, so another build's or anyone's `sleep` never counts. */
    private val nap = "600.${ProcessHandle.current().pid()}"

    private val adb = Files.createTempFile("adb", "").toFile().apply {
        writeText(
            """
            #!/bin/sh
            # A fake adb: emulator-5554 answers, emulator-5556 is frozen (its shell never exits).
            case "${'$'}*" in
              devices) printf 'List of devices attached\nemulator-5556\tdevice\nemulator-5554\tdevice\n' ;;
              "-s emulator-5556 shell"*) exec sleep $nap ;;
              "-s emulator-5554 shell getprop ro.build.version.release") echo 16 ;;
              "-s emulator-5554 emu avd name") printf 'nzoz_pixel7_api36\nOK\n' ;;
              "-s emulator-5556 emu avd name") printf 'agentctl_pixel7_api36\nOK\n' ;;
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
        val left = ProcessHandle.allProcesses().filter { it.info().commandLine().orElse("").contains("sleep $nap") }.count()
        assertEquals(0, left)
    }

    /** A frozen emulator is told apart by its AVD name, so asking for it by name does not boot a second copy. */
    @Test
    fun aFrozenEmulatorIsListedWithItsAvd() {
        val listing = Devices.list(adb.path, probeSeconds = 2) {}
        assertEquals(listOf(Devices.Frozen("emulator-5556", "agentctl_pixel7_api36")), listing.frozen)
        assertEquals(
            "agentctl_pixel7_api36 is running as emulator-5556 but does not answer (adb shell timed out after 10 s): " +
                "restart it (adb -s emulator-5556 emu kill), or pass another --device",
            Message.deviceDoesNotAnswer("agentctl_pixel7_api36", "emulator-5556"),
        )
    }

    @AfterTest
    fun removeTheFakeAdb() {
        adb.delete()
    }

    @Test
    fun aStuckAdbServerSaysSo() {
        assertEquals(
            "adb devices did not answer within 60 s; the adb server may be stuck: run 'adb kill-server', or restart the emulator",
            Message.adbDidNotAnswer(Devices.LIST_SECONDS),
        )
    }

    /** An adb that fails says why, not that no device is connected (its listing is empty). */
    @Test
    fun anAdbDevicesThatFailsSaysWhy() {
        val broken = Files.createTempFile("adb", "").toFile().apply {
            writeText("#!/bin/sh\necho 'List of devices attached'\necho '  adb: failed to check server version: protocol fault  ' >&2\nexit 1\n")
            setExecutable(true)
        }
        try {
            val error = kotlin.test.assertFailsWith<AppCtlException> { Devices.list(broken.path) {} }
            assertEquals("cannot run `adb devices` (exit 1): adb: failed to check server version: protocol fault", error.message)
            broken.writeText("#!/bin/sh\nexit 2\n")
            val silent = kotlin.test.assertFailsWith<AppCtlException> { Devices.list(broken.path) {} }
            assertEquals("cannot run `adb devices` (exit 2)", silent.message)
        } finally {
            broken.delete()
        }
    }

    /** No adb at all names where the CLI looked for the SDK. */
    @Test
    fun aMissingAdbSaysWhereTheSdkComesFrom() {
        val root = Files.createTempDirectory("sdk-root").toFile()
        try {
            val error = kotlin.test.assertFailsWith<AppCtlException> {
                Devices.list("/nonexistent/adb", missing = AndroidSdk.missingAdb(root, emptyMap())) {}
            }
            assertEquals(
                "cannot run adb (/nonexistent/adb): no Android SDK found: set ANDROID_HOME, or sdk.dir in local.properties",
                error.message,
            )
            val sdk = File(root, "sdk").apply { mkdirs() }
            assertEquals(
                "no adb in ${sdk.path}/platform-tools (from ANDROID_HOME), nor on PATH: set ANDROID_HOME, or sdk.dir in " +
                    "local.properties, to the Android SDK",
                AndroidSdk.missingAdb(root, mapOf("ANDROID_HOME" to sdk.path)),
            )
            File(root, "local.properties").writeText("sdk.dir=${sdk.path}\n")
            assertEquals(
                "no adb in ${sdk.path}/platform-tools (from local.properties' sdk.dir), nor on PATH: set ANDROID_HOME, or " +
                    "sdk.dir in local.properties, to the Android SDK",
                AndroidSdk.missingAdb(root, emptyMap()),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
