package io.github.olbartek.agentctl

import java.net.URI

/** A push notification `device push <name> [tap]` delivers. */
public class AgentPush<A>(
    /** What `device push` calls it, e.g. `order-shipped`. */
    public val name: String,
    /** One line for the docs, e.g. `Order #1002 has shipped.` */
    public val help: String,
    /** Sent when the push arrives while the app is in front; `null` when the app does nothing then. */
    public val received: A? = null,
    /** Sent when the user taps the notification (`device push <name> tap`). */
    public val opened: A,
)

/**
 * Which parts of the device an app lets scripts drive, and the root actions the device's events become
 * (CONTRACT.md §2.4). An app that configures nothing ([none]) has no `device` command at all.
 */
public class AgentDeviceConfig<A>(
    /**
     * The permission services `device permission` sets and the app's fakes request, e.g. `notifications`. App
     * vocabulary, like mock methods. `biometry` is reserved.
     */
    public val permissions: List<String> = emptyList(),
    /** The action a link opens, or `null` for a link the app does not handle (`device open-url`). */
    public val openUrl: ((URI) -> A?)? = null,
    /** The pushes `device push` delivers. */
    public val pushes: List<AgentPush<A>> = emptyList(),
    /** The action for the app entering a phase (`device background|foreground`), or `null` for none. */
    public val scenePhase: ((DevicePhase) -> A?)? = null,
    /** Whether `device relaunch` exists. */
    public val relaunch: Boolean = false,
    /** The error code every mocked call fails with while offline; non-`null` enables `device network`. */
    public val offlineErrorCode: String? = null,
    /** Whether `device location` exists. */
    public val location: Boolean = false,
    /** Whether `device biometry` exists. */
    public val biometry: Boolean = false,
) {
    init {
        require("biometry" !in permissions) { "'biometry' is reserved: it names the biometric prompt" }
    }

    /** What scripts can address, without the actions: for the docs, `screens` and the runner's checks. */
    public val surface: DeviceSurface
        get() {
            val subcommands = mutableListOf<DeviceSurface.Subcommand>()
            if (permissions.isNotEmpty()) subcommands += listOf(DeviceSurface.Subcommand.PERMISSION, DeviceSurface.Subcommand.PROMPT)
            if (openUrl != null) subcommands += DeviceSurface.Subcommand.OPEN_URL
            if (pushes.isNotEmpty()) subcommands += DeviceSurface.Subcommand.PUSH
            if (scenePhase != null) subcommands += listOf(DeviceSurface.Subcommand.BACKGROUND, DeviceSurface.Subcommand.FOREGROUND)
            if (relaunch) subcommands += DeviceSurface.Subcommand.RELAUNCH
            if (offlineErrorCode != null) subcommands += DeviceSurface.Subcommand.NETWORK
            if (location) subcommands += DeviceSurface.Subcommand.LOCATION
            if (biometry) subcommands += DeviceSurface.Subcommand.BIOMETRY
            return DeviceSurface(
                subcommands = subcommands,
                services = permissions,
                pushes = pushes.map { DeviceSurface.Push(it.name, it.help) },
                offlineErrorCode = offlineErrorCode,
            )
        }

    public companion object {
        /** No device capability: no `device` command. */
        public fun <A> none(): AgentDeviceConfig<A> = AgentDeviceConfig()
    }
}

/** The `device` subcommands an app has, and the names they take: an [AgentDeviceConfig] without its actions. */
public data class DeviceSurface(
    /** The subcommands this app has, in the fixed order. */
    val subcommands: List<Subcommand>,
    /** The permission services `device permission` takes. */
    val services: List<String>,
    /** The pushes `device push` delivers. */
    val pushes: List<Push>,
    /** The error code mocked calls fail with offline, or `null` without `device network`. */
    val offlineErrorCode: String?,
) {
    /** In the contract's fixed order (CONTRACT.md §2.4). */
    public enum class Subcommand(
        /** How scripts spell it, e.g. `open-url`. */
        public val rawValue: String,
    ) {
        PERMISSION("permission"),
        PROMPT("prompt"),
        OPEN_URL("open-url"),
        PUSH("push"),
        BACKGROUND("background"),
        FOREGROUND("foreground"),
        RELAUNCH("relaunch"),
        NETWORK("network"),
        LOCATION("location"),
        BIOMETRY("biometry"),
        ;

        public companion object {
            /** The subcommand spelled [rawValue], or `null`. */
            public fun of(rawValue: String): Subcommand? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /** A push's name and its line in the docs. */
    public data class Push(
        /** What `device push` calls it. */
        val name: String,
        /** Its line in the docs. */
        val help: String,
    )

    /** Whether `device` is a runtime command here. */
    val exists: Boolean get() = subcommands.isNotEmpty()

    /** Whether the app can show a system prompt (a permission's, or the biometric one): what `prompt=` reports. */
    val hasPrompts: Boolean get() = Subcommand.PROMPT in subcommands || Subcommand.BIOMETRY in subcommands

    /** The [CommandDoc]s of `device`, one per subcommand, as `screens` and the docs list them. */
    val commandDocs: List<CommandDoc>
        get() = subcommands.map { sub ->
            val (argument, help) = when (sub) {
                Subcommand.PERMISSION -> "permission <service> <not-determined|granted|denied|limited>" to
                    "Set a permission as if changed in Settings. Services: ${services.joinToString(", ")}."
                Subcommand.PROMPT -> "prompt <allow|deny|limited>" to
                    "Answer the system permission prompt the app is showing (prompt=<service>)."
                Subcommand.OPEN_URL -> "open-url <url>" to "Open a link in the app, as a tapped deep link or universal link."
                Subcommand.PUSH -> "push <name> [tap]" to
                    "Deliver a push notification while the app is in front; with tap, open the app from it. Pushes: " +
                    pushes.joinToString(", ") { "${it.name} (${it.help})" } + "."
                Subcommand.BACKGROUND -> "background" to
                    "Send the app to the background; screen commands fail until device foreground."
                Subcommand.FOREGROUND -> "foreground" to "Bring the app back to the foreground."
                Subcommand.RELAUNCH -> "relaunch" to
                    "Quit and relaunch the app: its state starts over, while what its clients stored and the device's " +
                    "settings stay."
                Subcommand.NETWORK -> "network <online|offline>" to
                    "Turn the network off or on. Offline, every mocked call fails with ${offlineErrorCode ?: ""}."
                Subcommand.LOCATION -> "location <lat>,<lon>|none" to
                    "Set the device's location, e.g. 52.2297,21.0122, or none."
                Subcommand.BIOMETRY -> "biometry <enrolled|unenrolled|match|fail>" to
                    "Enroll or remove Face ID / Touch ID, or answer the biometric prompt the app is showing (prompt=biometry)."
            }
            CommandDoc(name = "device", argument = argument, help = help, source = "runtime")
        }

    public companion object {
        /** No `device` command. */
        public val NONE: DeviceSurface = DeviceSurface(emptyList(), emptyList(), emptyList(), null)
    }
}
