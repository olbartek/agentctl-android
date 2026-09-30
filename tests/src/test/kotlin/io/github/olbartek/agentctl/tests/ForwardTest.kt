package io.github.olbartek.agentctl.tests

import com.sun.net.httpserver.HttpServer
import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What this guards: `adb forward`s do not pile up per device. A launch removes the forward its app's last launch on
 * that device left (the one `bridge.json` records) before making its own, whatever port it takes, and leaves every
 * other forward alone. The device is a fake `adb` that logs what it is asked; the app's bridge is a stand-in served
 * here on the port the launch is given.
 */
class ForwardTest {
    private val repo = Files.createTempDirectory("forward").toFile().apply { File(this, "settings.gradle.kts").writeText("") }
    private val calls = File(repo, "adb-calls.txt")
    private val appId = TinyAppConfig.appCtl.applicationId

    /** What the stand-in bridge says its platform is: the Android app's own, or another build of it (`null`: none). */
    @Volatile private var platform: String? = "android"

    /** What it says on `POST /run`: another app may take the port between the launch and the script. */
    @Volatile private var runPlatform: String? = "android"
    private val bridge = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            val body = "> launch\n  screen=items items=3".toByteArray()
            exchange.responseHeaders.add("X-Appctl-Exit", "0")
            exchange.responseHeaders.add("X-Appctl-App", appId)
            val says = if (exchange.requestMethod == "POST") runPlatform else platform
            says?.let { exchange.responseHeaders.add("X-Appctl-Platform", it) }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val port = bridge.address.port

    init {
        File(repo, "sdk/platform-tools/adb").apply {
            parentFile.mkdirs()
            writeText(
                """
                #!/bin/sh
                echo "${'$'}*" >> "${calls.path}"
                case "${'$'}*" in
                  devices) printf 'List of devices attached\nemulator-5554\tdevice\n' ;;
                  *"getprop ro.build.version.release") echo 16 ;;
                  *"emu avd name") printf 'agentctl_pixel7_api36\nOK\n' ;;
                  *"shell pidof"*) exit 1 ;;
                  "forward --list") printf 'emulator-5554 tcp:8766 tcp:8766\nemulator-5554 tcp:8790 tcp:8790\nemulator-5556 tcp:8766 tcp:8766\n' ;;
                  *) exit 0 ;;
                esac
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        // What the app's last launch on emulator-5554 recorded: its bridge on 8766.
        File(repo, ".appctl/bridge.json").apply {
            parentFile.mkdirs()
            writeText(
                """{"appId":"$appId","device":"emulator-5554","launchedAt":"2026-09-30T10:00:00Z","platform":"android","port":8766}""",
            )
        }
    }

    @AfterTest
    fun tearDown() {
        bridge.stop(0)
        repo.deleteRecursively()
    }

    private fun launch() = cli(
        "app", "launch", "--no-build", "--port", "$port",
        config = TinyAppConfig.appCtl,
        environment = mapOf("ANDROID_HOME" to File(repo, "sdk").path),
        workingDirectory = repo,
    )

    @Test
    fun aLaunchRemovesItsOwnEarlierForwardAndNoOther() {
        val result = launch()
        assertEquals(0, result.status, result.combined)
        val log = calls.readLines()
        val removes = log.filter { "forward --remove" in it }
        assertEquals(listOf("-s emulator-5554 forward --remove tcp:8766"), removes)
        val created = log.indexOf("-s emulator-5554 forward tcp:$port tcp:$port")
        assertTrue(created > log.indexOf(removes.single()), log.joinToString("\n"))
        // And the new launch is what bridge.json now records.
        assertTrue("\"port\" : $port" in File(repo, ".appctl/bridge.json").readText())
    }

    /**
     * The same app id on another platform (the iOS build of a two-platform app, holding the port) is not the app just
     * launched, and neither is a bridge that gives no platform: the launch fails rather than talk to it (CONTRACT.md
     * §8.4). With `--port` it does not move on to another port.
     */
    @Test
    fun aBridgeOfTheSameAppOnAnotherPlatformIsNotTakenForIt() {
        for ((answering, shown) in listOf("ios" to "ios", null to "no X-Appctl-Platform")) {
            platform = answering
            val result = launch()
            assertEquals(3, result.status, result.combined)
            val hint = if (answering == null) " (or the installed app predates X-Appctl-Platform: launch without --no-build)" else ""
            assertEquals(
                "error: the app's agent bridge on 127.0.0.1:$port answers as $appId ($shown), not $appId (android): another app " +
                    "holds that port; pass --port or set APPCTL_PORT$hint\n",
                result.err,
            )
            // Nothing half-launched is left behind: the forward made for it is removed.
            assertTrue("-s emulator-5554 forward --remove tcp:$port" in calls.readLines(), calls.readText())
        }
    }

    /** Every answer is checked, not only the launch's: a script answered by the app's iOS build is not this app's run. */
    @Test
    fun appTestChecksEveryAnswerNotOnlyTheLaunchs() {
        File(repo, "examples/tinyapp/scenarios/one.appctl").apply {
            parentFile.mkdirs()
            writeText("expect screen=items\n")
        }
        runPlatform = "ios"
        val result = cli(
            "app", "test", "--no-build", "--port", "$port",
            config = TinyAppConfig.appCtl,
            environment = mapOf("ANDROID_HOME" to File(repo, "sdk").path),
            workingDirectory = repo,
        )
        assertEquals(3, result.status, result.combined)
        assertTrue(
            "answers as $appId (ios), not $appId (android): another app holds that port" in result.out,
            result.combined,
        )
    }
}
