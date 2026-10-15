package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What this guards: the real-OS `app` commands (`permission`, `openurl`, `push`, `location`, `appearance`) run exactly
 * these `adb` invocations on the resolved device, and reject a malformed location, `push` and `location clear` before
 * touching it (CONTRACT.md §2.4; agentctl-ios's RealDeviceCommandsTests, with Android's differences). The device is a
 * fake `adb` that logs what it is asked.
 */
class RealDeviceCommandsTest {
    private val repo = Files.createTempDirectory("real-device").toFile().apply { File(this, "settings.gradle.kts").writeText("") }
    private val calls = File(repo, "adb-calls.txt")
    private val appId = TinyAppConfig.appCtl.applicationId

    /** A fake SDK whose one device is [serial]; `am start` answers [amSays]. */
    private fun sdk(serial: String = "emulator-5554", amSays: String = "Status: ok") = File(repo, "sdk").apply {
        File(this, "platform-tools/adb").apply {
            parentFile.mkdirs()
            writeText(
                """
                #!/bin/sh
                case "${'$'}*" in
                  devices) printf 'List of devices attached\n$serial\tdevice\n' ;;
                  *"getprop ro.build.version.release") echo 16 ;;
                  *"getprop ro.product.model") echo 'Pixel 7' ;;
                  *"emu avd name") printf 'agentctl_pixel7_api36\nOK\n' ;;
                  *" shell am start "*) echo "${'$'}*" >> "${calls.path}"; echo '$amSays' ;;
                  *" shell "*|*" emu geo "*) echo "${'$'}*" >> "${calls.path}" ;;
                  *) exit 0 ;;
                esac
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
    }

    @AfterTest
    fun tearDown() {
        repo.deleteRecursively()
    }

    private fun run(vararg args: String, sdk: File = sdk()) = cli(
        "app", *args,
        config = TinyAppConfig.appCtl,
        environment = mapOf("ANDROID_HOME" to sdk.path),
        workingDirectory = repo,
    )

    private fun called(): List<String> = if (calls.isFile) calls.readLines() else emptyList()

    @Test
    fun eachCommandRunsItsAdbInvocation() {
        val on = "on agentctl_pixel7_api36 (Android 16) [emulator-5554]"
        val cases = listOf(
            listOf("permission", "grant", "CAMERA") to "permission CAMERA: granted for $appId $on",
            listOf("permission", "revoke", "android.permission.POST_NOTIFICATIONS") to
                "permission android.permission.POST_NOTIFICATIONS: revoked for $appId $on",
            listOf("permission", "reset", "CAMERA") to "permission CAMERA: reset for $appId $on",
            listOf("openurl", "myapp://item/2?a=1&b=it's") to "opened myapp://item/2?a=1&b=it's $on",
            listOf("location", "52.2297,21.0122") to "location set to 52.2297,21.0122 $on",
            listOf("appearance", "dark") to "appearance dark $on",
            listOf("appearance", "light") to "appearance light $on",
        )
        for ((args, line) in cases) {
            val result = run(*args.toTypedArray())
            assertEquals(0, result.status, result.combined)
            assertEquals("$line\n", result.out)
        }
        assertEquals(
            listOf(
                "-s emulator-5554 shell pm grant $appId android.permission.CAMERA",
                "-s emulator-5554 shell pm revoke $appId android.permission.POST_NOTIFICATIONS",
                "-s emulator-5554 shell pm reset-permissions -p $appId",
                "-s emulator-5554 shell am start -W -a android.intent.action.VIEW -d 'myapp://item/2?a=1&b=it'\\''s' $appId",
                "-s emulator-5554 emu geo fix 21.0122 52.2297",
                "-s emulator-5554 shell cmd uimode night yes",
                "-s emulator-5554 shell cmd uimode night no",
            ),
            called(),
        )
    }

    @Test
    fun aMalformedLocationPushAndClearTouchNothing() {
        val cases = listOf(
            listOf("location", "52.2,") to "error: app location needs <lat>,<lon> or clear, e.g. 52.2297,21.0122\n",
            listOf("location", "north") to "error: app location needs <lat>,<lon> or clear, e.g. 52.2297,21.0122\n",
            listOf("location", "clear") to
                "error: app location clear is not available on Android: an emulator keeps its last location; set another one\n",
            listOf("push", "payload.json") to
                "error: app push is not available on Android: nothing delivers a push to one app; use device push in a script\n",
        )
        for ((args, message) in cases) {
            val result = run(*args.toTypedArray())
            assertEquals(2, result.status, result.combined)
            assertEquals(message, result.err)
        }
        assertEquals(emptyList(), called())
    }

    @Test
    fun aLocationNeedsAnEmulator() {
        val result = run("location", "52.2297,21.0122", sdk = sdk(serial = "1A2B3C"))
        assertEquals(3, result.status, result.combined)
        assertEquals("error: app location needs an emulator\n", result.err)
        assertEquals(emptyList(), called())
    }

    @Test
    fun aLinkNoActivityTakesFails() {
        val result = run("openurl", "nope://x", sdk = sdk(amSays = "Error: Activity not started, unable to resolve Intent"))
        assertEquals(3, result.status, result.combined)
        assertEquals("error: adb shell am start failed; log: ${File(repo, ".appctl/logs/app-openurl.log").path}\n", result.err)
    }
}
