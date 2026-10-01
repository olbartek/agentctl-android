package io.github.olbartek.agentctl.cli

import io.github.olbartek.agentctl.runtime.AgentLaunchOptions
import io.github.olbartek.agentctl.runtime.HttpParser
import io.github.olbartek.agentctl.runtime.RunStatus
import java.io.File
import java.io.IOException
import java.net.BindException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Instant
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

    data class Launched(val device: Device, val port: Int, val report: String)

    /**
     * Launches the app with its bridge on [port], or — when it is `null` — on the first port from 8765 up that is
     * free on the Mac and on the device, then records the launch in `<outputPath>/bridge.json`.
     */
    fun launch(seed: String?, device: String?, latency: Int?, clearSession: Boolean, build: Boolean, port: Int?): Launched {
        val start = TimeSource.Monotonic.markNow()
        // With --no-build the app is used as it is installed, on a device already running: one that is not is not booted.
        val target = if (build) resolve(device) else find(device)
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
        // Stopped first, so a bridge it left listening does not count against the port it can have again, and the
        // forward its last launch here left goes with it: forwards do not pile up per device, whatever port this launch
        // takes. The port is chosen now, after the build and install, right before the launch.
        stop(target, log)
        removeOwnForward(target, log)
        var chosen = port ?: freePort(target, log)
        val component = config.applicationId + "/" + config.launchActivity
        var answer: BridgeClient.Response
        var retried = false
        while (true) {
            val forwarded = Shell.run(listOf(adb, "-s", target.serial, "forward", "tcp:$chosen", "tcp:$chosen"), root, log, append = true) == 0
            if (!forwarded) {
                // Taken on the Mac between the check and the forward: a scanned port moves on, a named one fails.
                if (port != null || retried) throw AppCtlException("adb forward tcp:$chosen tcp:$chosen failed; log: ${log.path}")
                retried = true
                chosen = freePort(target, log, after = chosen)
                continue
            }
            // The CLI connects to 127.0.0.1 only: a forward that does not hold it there would send it elsewhere.
            if (!holdsIpv4Loopback(chosen)) {
                Shell.run(listOf(adb, "-s", target.serial, "forward", "--remove", "tcp:$chosen"), root, log, append = true)
                throw AppCtlException("adb forward tcp:$chosen does not listen on 127.0.0.1:$chosen; log: ${log.path}")
            }
            val extras = mutableListOf("--ei", AgentLaunchOptions.PORT, chosen.toString())
            seed?.let { extras += listOf("--es", AgentLaunchOptions.SEED, shellQuoted(it)) }
            latency?.let { extras += listOf("--ei", AgentLaunchOptions.MOCK_LATENCY, it.toString()) }
            if (clearSession) extras += listOf("--ez", AgentLaunchOptions.CLEAR_SESSION, "true")
            adbOrThrow(listOf("shell", "am", "start", "-W", "-n", component) + extras, target, log)
            answer = try {
                BridgeClient(chosen).waitUntilReady()
            } catch (timeout: AppCtlException) {
                // No bridge answered. Our app is stopped first, so a slow start of our own does not read as a port held
                // by another; if something still listens on the device port, the app could not listen there: a scanned
                // port moves on once, a named one fails. Otherwise it is the plain timeout.
                stop(target, log)
                Shell.run(listOf(adb, "-s", target.serial, "forward", "--remove", "tcp:$chosen"), root, log, append = true)
                if (chosen !in deviceListening(target)) throw timeout
                if (port != null || retried) throw AppCtlException(Message.couldNotListen(chosen))
                retried = true
                chosen = freePort(target, log, after = chosen)
                continue
            }
            // Another app's bridge answered on this port. The app just launched is built from the same checkout as
            // this CLI and always says which app it is, so an answer without X-Appctl-App is foreign too (an older app
            // holding the port). A port found by the scan gets one more try, on the next free port above it.
            // The app id alone is not enough: an app's iOS build may hold the port with the same id.
            if (answer.isFrom(config.applicationId, BridgeState.PLATFORM)) break
            if (port != null || retried) {
                // Leave nothing half-launched: our app without a bridge, a forward to the other app.
                stop(target, log)
                Shell.run(listOf(adb, "-s", target.serial, "forward", "--remove", "tcp:$chosen"), root, log, append = true)
                throw AppCtlException(Message.anotherApp(chosen, answer.app, answer.platform, config.applicationId))
            }
            retried = true
            stop(target, log)
            Shell.run(listOf(adb, "-s", target.serial, "forward", "--remove", "tcp:$chosen"), root, log, append = true)
            chosen = freePort(target, log, after = chosen)
        }
        val port = chosen
        val snapshot = answer.body
        try {
            BridgeState(BridgeState.PLATFORM, target.serial, port, config.applicationId, Instant.now()).save(layout)
        } catch (error: IOException) {
            throw AppCtlException("cannot write ${config.outputPath}/bridge.json: $error")
        }
        val seconds = String.format(Locale.ROOT, "%.1f", start.elapsedNow().inWholeMilliseconds / 1000.0)
        val line = snapshot.split("\n").drop(1).firstOrNull()?.trim() ?: ""
        return Launched(target, port, "launched on ${target.label} [${target.serial}] at 127.0.0.1:$port in ${seconds}s: $line")
    }

    /**
     * Stops the app and waits (up to 5 s) until its process has gone, so the port its bridge held is free again and
     * a relaunch can have it back.
     */
    private fun stop(device: Device, log: File) {
        Shell.run(listOf(adb, "-s", device.serial, "shell", "am", "force-stop", config.applicationId), root, log, append = true)
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < 5.seconds) {
            val pid = Shell.capture(listOf(adb, "-s", device.serial, "shell", "pidof", config.applicationId))?.trim()
            if (pid.isNullOrEmpty()) return
            Thread.sleep(100)
        }
    }

    /** The ports something listens on, on the device. */
    private fun deviceListening(device: Device): Set<Int> = Ports.listening(
        Shell.capture(listOf(adb, "-s", device.serial, "shell", "cat", "/proc/net/tcp", "/proc/net/tcp6")) ?: "",
    )

    /**
     * Removes the forward this app's last launch on [device] left (recorded in `bridge.json`), once the app is stopped;
     * a relaunch then gets that port back, and no other forward is touched.
     */
    private fun removeOwnForward(device: Device, log: File) {
        val recorded = try {
            BridgeState.load(layout)
        } catch (_: Unreadable) {
            null
        }
        val forwards = Shell.capture(listOf(adb, "forward", "--list")) ?: ""
        Ports.ownForward(recorded, device.serial, config.applicationId, forwards)?.let { own ->
            Shell.run(listOf(adb, "-s", device.serial, "forward", "--remove", "tcp:$own"), root, log, append = true)
        }
    }

    /**
     * The first port of [Ports.SCAN] above [after] (if given) that is free on the Mac (nothing answers or is bound
     * there: another device's forward, an iOS simulator's bridge) and on the device (nothing listens on it).
     */
    private fun freePort(device: Device, log: File, after: Int? = null): Int {
        val listening = deviceListening(device)
        return Ports.firstFree(after) { it !in listening && freeOnHost(it) }
            ?: throw AppCtlException(
                "no free port for the app's agent bridge in ${Ports.SCAN.first}-${Ports.SCAN.last}: pass --port or set ${Ports.ENVIRONMENT_VARIABLE}",
            )
    }

    fun screenshot(device: Device, file: File) {
        val status = Shell.captureTo(listOf(adb, "-s", device.serial, "exec-out", "screencap", "-p"), file)
        if (status != 0) throw AppCtlException("screenshot failed (adb exec-out screencap exited $status)")
    }

    /**
     * An `adb` serial, an AVD name (a running emulator's, or one to boot), a model name, or — with no name — the only
     * connected device.
     */
    fun resolve(nameOrSerial: String?): Device = locate(nameOrSerial, boots = true)

    /** Whether [serial] is connected and answers (quietly: no warning for one that does not). */
    fun isConnected(serial: String): Boolean = Devices.list(adb) {}.ready.any { it.serial == serial }

    /** [resolve], without booting: an AVD that is not running fails as not booted. For the `app` device commands. */
    fun find(nameOrSerial: String?): Device = locate(nameOrSerial, boots = false)

    private fun locate(nameOrSerial: String?, boots: Boolean): Device {
        val listing = Devices.list(adb) { cli.io.err.println("warning: $it") }
        val devices = listing.ready
        if (nameOrSerial == null) {
            return devices.singleOrNull() ?: throw AppCtlException(
                if (devices.isEmpty()) "no device connected (start an emulator, or pass --device <AVD name>)"
                else "several devices connected (${devices.joinToString(", ") { it.serial }}); pass --device <serial>",
            )
        }
        // Never picked silently: two emulators of one AVD (-read-only) or two phones of one model share a name.
        val matches = devices.filter { it.serial == nameOrSerial || it.name == nameOrSerial }
        if (matches.size > 1) throw AppCtlException(Message.severalDevices(nameOrSerial, matches), RunStatus.USAGE)
        matches.singleOrNull()?.let { return it }
        // Running but frozen: booting its AVD again would only start a second copy beside it.
        listing.frozen.firstOrNull { it.serial == nameOrSerial || it.avd == nameOrSerial }?.let { frozen ->
            throw AppCtlException(Message.deviceDoesNotAnswer(nameOrSerial, frozen.serial))
        }
        val avds = Shell.capture(listOf(AndroidSdk.emulator(root, cli.environment), "-list-avds"))?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (nameOrSerial !in avds) {
            val names = (devices.map { it.name } + avds).toSortedSet().joinToString(", ")
            throw AppCtlException("no device or AVD named '$nameOrSerial'. Available: $names")
        }
        if (!boots) throw AppCtlException(Message.notBooted(cli, nameOrSerial, "AVD"))
        return boot(nameOrSerial, devices.map { it.serial }.toSet())
    }

    private fun boot(avd: String, before: Set<String>): Device {
        val log = File(layout.logs, "emulator-$avd.log").also { it.parentFile.mkdirs() }
        ProcessBuilder(bootCommand(AndroidSdk.emulator(root, cli.environment), avd))
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < 300.seconds) {
            // Quietly: a device that does not answer was reported once already, by resolve.
            val booted = Devices.list(adb) {}.ready.firstOrNull { it.serial !in before && it.name == avd }
            if (booted != null && getprop(booted.serial, "sys.boot_completed") == "1") return booted
            Thread.sleep(1000)
        }
        throw AppCtlException("the emulator $avd did not boot within 5 minutes; log: ${log.path}")
    }

    private fun getprop(serial: String, property: String): String? = Devices.getprop(adb, serial, property)

    private fun adbOrThrow(arguments: List<String>, device: Device, log: File) {
        val status = Shell.run(listOf(adb, "-s", device.serial) + arguments, root, log, append = true)
        if (status != 0) throw AppCtlException("adb ${arguments.take(3).joinToString(" ")} failed; log: ${log.path}")
    }

    companion object {
        private val IPV4_LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        private val IPV6_LOOPBACK: InetAddress = InetAddress.getByName("::1")

        /**
         * Whether nothing on the Mac holds [port] on either loopback, as the reference checks it (CONTRACT.md §8.6).
         * A port is taken when a connection to 127.0.0.1 or ::1 is answered, or does not come back at all (a listener
         * whose backlog is full, a stopped app: only a refusal means nothing is there), or when a bind to 127.0.0.1 or
         * ::1 fails. The binds reuse the address, so the TIME_WAIT connections of the last run on that port do not
         * count; a Mac without IPv6 has no ::1 to hold anything.
         */
        fun freeOnHost(port: Int): Boolean = listOf(IPV4_LOOPBACK, IPV6_LOOPBACK).none { address -> heldOn(address, port) }

        private fun heldOn(address: InetAddress, port: Int): Boolean {
            try {
                // Not refused within a second: a listener that no longer accepts (CONTRACT.md §8.6).
                Socket().use { it.connect(InetSocketAddress(address, port), 1000) }
                return true
            } catch (_: SocketTimeoutException) {
                return true
            } catch (_: ConnectException) {
                // Refused: nothing listens there. See whether it can be bound.
            } catch (_: IOException) {
                // No such address here (IPv6 off): it can hold nothing.
                if (address == IPV6_LOOPBACK) return false
            }
            return try {
                ServerSocket().use {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress(address, port), 1)
                }
                false
            } catch (_: BindException) {
                true
            } catch (_: IOException) {
                address == IPV4_LOOPBACK
            }
        }

        /**
         * Whether something holds 127.0.0.1:[port] (a bind there fails): after `adb forward`, that the forward really
         * listens where the CLI will connect, and not beside another listener on the other loopback.
         */
        fun holdsIpv4Loopback(port: Int): Boolean = try {
            ServerSocket().use {
                it.reuseAddress = true
                it.bind(InetSocketAddress(IPV4_LOOPBACK, port), 1)
            }
            false
        } catch (_: IOException) {
            true
        }

        /**
         * The line an AVD is booted with. `-no-metrics -crash-report-mode never`: after an emulator crash, a plain
         * boot stops at the crash-report consent prompt and never opens its adb ports, so it would never finish.
         */
        fun bootCommand(emulator: String, avd: String): List<String> = listOf(
            emulator, "-avd", avd, "-no-snapshot-save", "-no-boot-anim", "-no-metrics", "-crash-report-mode", "never",
        )

        /** `adb shell` joins its arguments into one device shell command line: quote a value for that shell. */
        fun shellQuoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}

/** The devices `adb` lists, each asked who it is. A device that does not answer is skipped, not waited for. */
internal object Devices {
    /** How long a device gets to answer one question: a frozen emulator never does. */
    const val PROBE_SECONDS: Long = 10

    /** How long `adb devices` gets, which may have to start the adb server. */
    const val LIST_SECONDS: Long = 60

    /** A device `adb` lists as ready whose shell does not answer: a frozen emulator, and its AVD if its console says. */
    data class Frozen(val serial: String, val avd: String?)

    /** What `adb devices` lists: the devices that answer, and those that do not. */
    data class Listing(val ready: List<Device>, val frozen: List<Frozen>)

    /**
     * The devices `adb devices` lists as ready, with their names and Android releases. One whose shell does not
     * answer within [probeSeconds] (a frozen emulator is still listed as `device`) is left out, and [warn] says so.
     */
    fun connected(adb: String, probeSeconds: Long = PROBE_SECONDS, warn: (String) -> Unit): List<Device> =
        list(adb, probeSeconds, warn).ready

    /** [connected], and the devices it left out because they do not answer. */
    fun list(adb: String, probeSeconds: Long = PROBE_SECONDS, warn: (String) -> Unit): Listing {
        val frozen = mutableListOf<Frozen>()
        // The listing gets the usual time: it may start the adb server first. Only each device's answer is short.
        val started = TimeSource.Monotonic.markNow()
        val output = Shell.capture(listOf(adb, "devices"), timeoutSeconds = LIST_SECONDS) ?: throw AppCtlException(
            if (started.elapsedNow() >= LIST_SECONDS.seconds) Message.adbDidNotAnswer(LIST_SECONDS) else "cannot run adb ($adb)",
        )
        val ready = output.lines().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2 || parts[1] != "device") return@mapNotNull null
            val serial = parts[0]
            // Asked first: it is how a device that does not answer is found out, before anything else waits on it.
            val release = Shell.capture(listOf(adb, "-s", serial, "shell", "getprop", "ro.build.version.release"), timeoutSeconds = probeSeconds)
            val avd = { avdName(adb, serial, probeSeconds) }
            if (release == null) {
                warn("$serial does not answer (adb shell timed out after $probeSeconds s); skipping it")
                // Its console may still answer (it did when the guest froze), so it can be told apart by name.
                frozen.add(Frozen(serial, avd()))
                return@mapNotNull null
            }
            val name = avd() ?: getprop(adb, serial, "ro.product.model", probeSeconds) ?: serial
            Device(serial, name, release.trim().ifEmpty { "?" })
        }
        return Listing(ready, frozen)
    }

    /** An emulator's AVD name, from its console; `null` for a device that is not an emulator. */
    private fun avdName(adb: String, serial: String, timeoutSeconds: Long): String? {
        if (!serial.startsWith("emulator-")) return null
        return Shell.capture(listOf(adb, "-s", serial, "emu", "avd", "name"), timeoutSeconds = timeoutSeconds)
            ?.lines()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** One system property of a device, `null` when it is empty or the device does not answer in time. */
    fun getprop(adb: String, serial: String, property: String, timeoutSeconds: Long = PROBE_SECONDS): String? =
        Shell.capture(listOf(adb, "-s", serial, "shell", "getprop", property), timeoutSeconds = timeoutSeconds)
            ?.trim()?.takeIf { it.isNotEmpty() }
}
