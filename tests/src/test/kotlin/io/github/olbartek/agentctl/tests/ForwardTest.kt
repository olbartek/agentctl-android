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
    private val bridge = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            val body = "> launch\n  screen=items items=3".toByteArray()
            exchange.responseHeaders.add("X-Appctl-Exit", "0")
            exchange.responseHeaders.add("X-Appctl-App", appId)
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

    @Test
    fun aLaunchRemovesItsOwnEarlierForwardAndNoOther() {
        val result = cli(
            "app", "launch", "--no-build", "--port", "$port",
            config = TinyAppConfig.appCtl,
            environment = mapOf("ANDROID_HOME" to File(repo, "sdk").path),
            workingDirectory = repo,
        )
        assertEquals(0, result.status, result.combined)
        val log = calls.readLines()
        val removes = log.filter { "forward --remove" in it }
        assertEquals(listOf("-s emulator-5554 forward --remove tcp:8766"), removes)
        val created = log.indexOf("-s emulator-5554 forward tcp:$port tcp:$port")
        assertTrue(created > log.indexOf(removes.single()), log.joinToString("\n"))
        // And the new launch is what bridge.json now records.
        assertTrue("\"port\" : $port" in File(repo, ".appctl/bridge.json").readText())
    }
}
