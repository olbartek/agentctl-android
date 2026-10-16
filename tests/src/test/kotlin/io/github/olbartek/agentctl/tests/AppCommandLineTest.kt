package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What this guards: the `app` commands' arguments are checked as agentctl-ios checks them. `--step-delay` must be a
 * finite number of seconds from 0 to 3600 (a usage error, exit 2, before anything is built), and `app location` takes
 * a southern latitude (`-33.86,151.21`), which would otherwise read as an option. An adb that fails says so, not that
 * no device is connected.
 *
 * The device is a fake `adb` on a temporary ANDROID_HOME: no device is needed.
 */
class AppCommandLineTest {
    private val root = Files.createTempDirectory("app-command-line").toFile()
    private val sdk = File(root, "sdk")
    private val adbCalls = File(root, "adb-calls.txt")

    init {
        File(root, "settings.gradle.kts").writeText("")
        File(sdk, "platform-tools/adb").apply {
            parentFile.mkdirs()
            writeText(
                """
                #!/bin/sh
                echo "${'$'}*" >> '${adbCalls.path}'
                case "${'$'}*" in
                  devices) printf 'List of devices attached\nemulator-5554\tdevice\n' ;;
                  *"shell getprop ro.build.version.release") echo 16 ;;
                  *"emu avd name") printf 'tiny_avd\nOK\n' ;;
                  *"emu geo fix"*) ;;
                  *) exit 1 ;;
                esac
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
    }

    @AfterTest
    fun removeTheRepository() {
        root.deleteRecursively()
    }

    private fun run(vararg args: String) =
        cli(*args, config = TinyAppConfig.appCtl, environment = mapOf("ANDROID_HOME" to sdk.path), workingDirectory = root)

    @Test
    fun aStepDelayOutsideZeroToAnHourIsAUsageError() {
        for ((value, shown) in listOf("Infinity" to "inf", "NaN" to "nan", "-1" to "-1.0", "3601" to "3601.0")) {
            val result = run("app", "test", "--no-build", "--step-delay", value)
            assertEquals(2, result.status, "$value: ${result.combined}")
            assertTrue(result.err.startsWith("Usage: "), result.err)
            assertTrue("--step-delay must be between 0 and 3600 seconds, not '$shown'\n" in result.err, result.err)
            assertEquals(false, adbCalls.exists(), "$value reached adb")
        }
    }

    @Test
    fun aSouthernLatitudeIsTheLocationNotAnOption() {
        for (args in listOf(
            listOf("app", "location", "-33.8,151.2"),
            listOf("app", "location", "--device", "emulator-5554", "-33.8,151.2"),
            listOf("app", "location", "-33.8,151.2", "--device", "emulator-5554"),
        )) {
            adbCalls.delete()
            val result = run(*args.toTypedArray())
            assertEquals(0, result.status, "$args: ${result.combined}")
            assertEquals("location set to -33.8,151.2 on tiny_avd (Android 16) [emulator-5554]\n", result.out)
            assertTrue("-s emulator-5554 emu geo fix 151.2 -33.8" in adbCalls.readText(), adbCalls.readText())
        }
    }

    @Test
    fun anOptionAfterLocationIsStillAnOption() {
        val result = run("app", "location", "-33.8,151.2", "--nope")
        assertEquals(2, result.status, result.combined)
        assertTrue("no such option --nope" in result.err, result.err)
    }

    @Test
    fun aBrokenAdbIsReportedAsBrokenNotAsNoDevice() {
        File(sdk, "platform-tools/adb").writeText("#!/bin/sh\necho 'adb: failed to check server version: protocol fault' >&2\nexit 1\n")
        val result = run("app", "screenshot", "shot.png")
        assertEquals(3, result.status, result.combined)
        assertEquals("error: cannot run `adb devices` (exit 1): adb: failed to check server version: protocol fault\n", result.err)
    }
}
