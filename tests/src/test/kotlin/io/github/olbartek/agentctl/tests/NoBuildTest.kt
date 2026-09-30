package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What this guards: with `--no-build`, `app launch` and `app test` use the app as installed on a device that is already
 * running. An AVD that is not running is not booted: one not-booted line and exit 3, before any scenario, never a
 * failure per scenario (0.5 L8, as agentctl-ios prints it). The SDK here is a fake: no device is connected, and the
 * emulator knows one AVD.
 */
class NoBuildTest {
    private val sdk = Files.createTempDirectory("sdk").toFile()

    init {
        fun tool(path: String, script: String) = File(sdk, path).apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n$script\n")
            setExecutable(true)
        }
        tool("platform-tools/adb", """[ "${'$'}1" = devices ] && echo "List of devices attached"; exit 0""")
        // Booting would be the bug: the fake records it, and never boots.
        tool("emulator/emulator", """[ "${'$'}1" = -list-avds ] && { echo agentctl_pixel7_api36; exit 0; }; touch "${sdk.path}/booted"; exit 1""")
    }

    @AfterTest
    fun removeTheFakeSdk() {
        sdk.deleteRecursively()
    }

    private fun run(vararg args: String) = cli(*args, config = TinyAppConfig.appCtl, environment = mapOf("ANDROID_HOME" to sdk.path))

    private val notBooted = "error: agentctl_pixel7_api36 [AVD] is not booted; boot it, or run ./tinyctl app launch\n"

    @Test
    fun appTestDoesNotBootAndFailsOnce() {
        val result = run("app", "test", "--no-build", "--device", "agentctl_pixel7_api36")
        assertEquals(3, result.status, result.combined)
        assertEquals("", result.out)
        assertEquals(notBooted, result.err)
        assertEquals(false, File(sdk, "booted").exists())
    }

    @Test
    fun appLaunchDoesNotBootEither() {
        val result = run("app", "launch", "--no-build", "--device", "agentctl_pixel7_api36")
        assertEquals(3, result.status, result.combined)
        assertEquals(notBooted, result.err)
        assertEquals(false, File(sdk, "booted").exists())
    }
}
