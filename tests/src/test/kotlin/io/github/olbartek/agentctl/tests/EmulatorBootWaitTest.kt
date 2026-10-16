package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What this guards: while an emulator boots, an `adb devices` that fails once (its server restarting, say) is waited
 * out with the rest of the boot, not reported as the launch's error. The SDK is a fake: its `emulator` exits at once,
 * and its `adb` lists nothing, then fails once, then lists the booted emulator.
 */
class EmulatorBootWaitTest {
    private val root = Files.createTempDirectory("boot-wait").toFile()
    private val sdk = File(root, "sdk")
    private val calls = File(root, "devices-calls")

    init {
        File(root, "settings.gradle.kts").writeText("")
        script(File(root, "gradlew"), "echo 'install failed on purpose'; exit 1")
        script(File(sdk, "emulator/emulator"), """[ "${'$'}1" = -list-avds ] && echo tiny_avd; exit 0""")
        script(
            File(sdk, "platform-tools/adb"),
            """
            case "${'$'}*" in
              devices)
                echo x >> '${calls.path}'
                case ${'$'}(wc -l < '${calls.path}' | tr -d ' ') in
                  1) printf 'List of devices attached\n' ;;
                  2) echo 'adb: failed to check server version: protocol fault' >&2; exit 1 ;;
                  *) printf 'List of devices attached\nemulator-5554\tdevice\n' ;;
                esac ;;
              *"shell getprop ro.build.version.release") echo 16 ;;
              *"shell getprop sys.boot_completed") echo 1 ;;
              *"emu avd name") printf 'tiny_avd\nOK\n' ;;
              *) exit 0 ;;
            esac
            """.trimIndent(),
        )
    }

    private fun script(file: File, body: String) = file.apply {
        parentFile.mkdirs()
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }

    @AfterTest
    fun removeTheRepository() {
        root.deleteRecursively()
    }

    @Test
    fun aFailingAdbDevicesDuringTheBootIsWaitedOut() {
        val result = cli(
            "app", "launch", "--device", "tiny_avd",
            config = TinyAppConfig.appCtl,
            environment = mapOf("ANDROID_HOME" to sdk.path),
            workingDirectory = root,
        )
        // Booted, then the (fake) install failed: the launch got past the boot.
        assertEquals(3, result.status, result.combined)
        assertTrue("${TinyAppConfig.appCtl.gradle.install} failed; log: " in result.err, result.err)
        assertFalse("adb devices" in result.err, result.err)
        assertEquals(3, calls.readLines().size)
    }
}
