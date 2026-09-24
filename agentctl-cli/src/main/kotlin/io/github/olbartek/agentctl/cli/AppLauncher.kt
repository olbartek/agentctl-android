package io.github.olbartek.agentctl.cli

import io.github.olbartek.agentctl.runtime.AgentLaunchOptions
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A device or emulator `adb` can see. */
internal data class Device(val serial: String, val name: String, val release: String) {
    val label: String get() = "$name (Android $release)"
}

/**
 * Finds a device, builds and installs the host app on it, and launches it with its launch arguments as intent
 * extras (CONTRACT.md §8.2), the bridge's port forwarded to the Mac.
 */
internal class AppLauncher(private val cli: Cli<*, *>, private val root: File) {
    private val config = cli.config
    private val layout = Layout(root, config.outputPath)
    private val adb = AndroidSdk.adb(root, cli.environment)

    data class Launched(val device: Device, val report: String)

    fun launch(seed: String?, device: String?, latency: Int?, clearSession: Boolean, build: Boolean, port: Int): Launched {
        val start = TimeSource.Monotonic.markNow()
        val target = resolve(device)
        val log = File(layout.logs, "app-launch.log")
        if (build) {
            val status = Shell.run(
                Gradle.command(root, listOf(config.gradle.install)),
                root,
                log,
                environment = mapOf("ANDROID_SERIAL" to target.serial),
            )
            if (status != 0) throw AppCtlException("${config.gradle.install} failed; log: ${log.path}")
        }
        adbOrThrow(listOf("forward", "tcp:$port", "tcp:$port"), target, log)
        Shell.run(listOf(adb, "-s", target.serial, "shell", "am", "force-stop", config.applicationId), root, log, append = true)
        val component = config.applicationId + "/" + config.launchActivity
        val extras = mutableListOf("--ei", AgentLaunchOptions.PORT, port.toString())
        seed?.let { extras += listOf("--es", AgentLaunchOptions.SEED, shellQuoted(it)) }
        latency?.let { extras += listOf("--ei", AgentLaunchOptions.MOCK_LATENCY, it.toString()) }
        if (clearSession) extras += listOf("--ez", AgentLaunchOptions.CLEAR_SESSION, "true")
        adbOrThrow(listOf("shell", "am", "start", "-W", "-n", component) + extras, target, log)
        val snapshot = BridgeClient(port).waitUntilReady()
        val seconds = String.format(Locale.ROOT, "%.1f", start.elapsedNow().inWholeMilliseconds / 1000.0)
        val line = snapshot.split("\n").drop(1).firstOrNull()?.trim() ?: ""
        return Launched(target, "launched on ${target.label} [${target.serial}] in ${seconds}s: $line")
    }

    fun screenshot(device: Device, file: File) {
        val status = Shell.captureTo(listOf(adb, "-s", device.serial, "exec-out", "screencap", "-p"), file)
        if (status != 0) throw AppCtlException("screenshot failed (adb exec-out screencap exited $status)")
    }

    /**
     * An `adb` serial, an AVD name (a running emulator's, or one to boot), a model name, or — with no name — the only
     * connected device.
     */
    fun resolve(nameOrSerial: String?): Device {
        val devices = connected()
        if (nameOrSerial == null) {
            return devices.singleOrNull() ?: throw AppCtlException(
                if (devices.isEmpty()) "no device connected (start an emulator, or pass --device <AVD name>)"
                else "several devices connected (${devices.joinToString(", ") { it.serial }}); pass --device <serial>",
            )
        }
        devices.firstOrNull { it.serial == nameOrSerial || it.name == nameOrSerial }?.let { return it }
        val avds = Shell.capture(listOf(AndroidSdk.emulator(root, cli.environment), "-list-avds"))?.lines()?.map { it.trim() } ?: emptyList()
        if (nameOrSerial !in avds) {
            val names = (devices.map { it.name } + avds).toSortedSet().joinToString(", ")
            throw AppCtlException("no device or AVD named '$nameOrSerial'. Available: $names")
        }
        return boot(nameOrSerial, devices.map { it.serial }.toSet())
    }

    private fun boot(avd: String, before: Set<String>): Device {
        val log = File(layout.logs, "emulator-$avd.log").also { it.parentFile.mkdirs() }
        ProcessBuilder(AndroidSdk.emulator(root, cli.environment), "-avd", avd, "-no-snapshot-save", "-no-boot-anim")
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < 300.seconds) {
            val booted = connected().firstOrNull { it.serial !in before && it.name == avd }
            if (booted != null && getprop(booted.serial, "sys.boot_completed") == "1") return booted
            Thread.sleep(1000)
        }
        throw AppCtlException("the emulator $avd did not boot within 5 minutes; log: ${log.path}")
    }

    private fun connected(): List<Device> {
        val output = Shell.capture(listOf(adb, "devices")) ?: throw AppCtlException("cannot run adb ($adb)")
        return output.lines().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2 || parts[1] != "device") return@mapNotNull null
            val serial = parts[0]
            val avd = if (serial.startsWith("emulator-")) {
                Shell.capture(listOf(adb, "-s", serial, "emu", "avd", "name"))?.lines()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
            val name = avd ?: getprop(serial, "ro.product.model") ?: serial
            Device(serial, name, getprop(serial, "ro.build.version.release") ?: "?")
        }
    }

    private fun getprop(serial: String, property: String): String? =
        Shell.capture(listOf(adb, "-s", serial, "shell", "getprop", property))?.trim()?.takeIf { it.isNotEmpty() }

    private fun adbOrThrow(arguments: List<String>, device: Device, log: File) {
        val status = Shell.run(listOf(adb, "-s", device.serial) + arguments, root, log, append = true)
        if (status != 0) throw AppCtlException("adb ${arguments.take(3).joinToString(" ")} failed; log: ${log.path}")
    }

    companion object {
        /** `adb shell` joins its arguments into one device shell command line: quote a value for that shell. */
        fun shellQuoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
