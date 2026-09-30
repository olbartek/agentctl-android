package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What this guards: `--device <name>` never picks one of several devices silently (0.5 L9, as agentctl-ios resolves a
 * simulator's name). Two emulators of one AVD (`-read-only`) or two phones of one model share a name: the command
 * fails with exit 2, lists each serial, and says to pass one. The SDK here is a fake that has both.
 */
class AmbiguousDeviceTest {
    private val sdk = Files.createTempDirectory("sdk").toFile()

    init {
        File(sdk, "platform-tools/adb").apply {
            parentFile.mkdirs()
            writeText(
                """
                #!/bin/sh
                case "${'$'}*" in
                  devices) printf 'List of devices attached\nemulator-5556\tdevice\nemulator-5554\tdevice\n1A2B3C\tdevice\n4D5E6F\tdevice\n' ;;
                  "-s 1A2B3C shell getprop ro.build.version.release") echo 15 ;;
                  *"shell getprop ro.build.version.release") echo 16 ;;
                  *"shell getprop ro.product.model") echo 'Pixel 7' ;;
                  *"emu avd name") printf 'agentctl_pixel7_api36\nOK\n' ;;
                  *) exit 1 ;;
                esac
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
    }

    @AfterTest
    fun removeTheFakeSdk() {
        sdk.deleteRecursively()
    }

    private fun run(vararg args: String) = cli(*args, config = TinyAppConfig.appCtl, environment = mapOf("ANDROID_HOME" to sdk.path))

    @Test
    fun twoEmulatorsOfOneAvdAreNotPickedBetween() {
        val result = run("app", "screenshot", File(sdk, "shot.png").path, "--device", "agentctl_pixel7_api36")
        assertEquals(2, result.status, result.combined)
        assertEquals(
            "error: several devices are named 'agentctl_pixel7_api36'; pass --device <serial>:\n" +
                "  emulator-5554  agentctl_pixel7_api36 (Android 16)\n" +
                "  emulator-5556  agentctl_pixel7_api36 (Android 16)\n",
            result.err,
        )
    }

    @Test
    fun twoPhonesOfOneModelAreNotPickedBetweenEither() {
        val result = run("app", "test", "--no-build", "--device", "Pixel 7")
        assertEquals(2, result.status, result.combined)
        assertEquals("", result.out)
        assertEquals(
            // Newest Android first, then by serial, whatever order adb lists them in.
            "error: several devices are named 'Pixel 7'; pass --device <serial>:\n" +
                "  4D5E6F  Pixel 7 (Android 16)\n" +
                "  1A2B3C  Pixel 7 (Android 15)\n",
            result.err,
        )
    }

    /** A serial is one device: it resolves (and the fake's screencap then fails, which is not what is tested). */
    @Test
    fun aSerialIsNeverAmbiguous() {
        val result = run("app", "screenshot", File(sdk, "shot.png").path, "--device", "emulator-5556")
        assertEquals(3, result.status, result.combined)
        assertEquals("error: screenshot failed (adb exec-out screencap exited 1)\n", result.err)
    }
}
