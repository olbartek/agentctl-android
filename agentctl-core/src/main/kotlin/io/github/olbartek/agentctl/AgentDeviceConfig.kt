package io.github.olbartek.agentctl

import java.net.URI

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
    public val pushes: List<AgentDevice.Push<A>> = emptyList(),
    /** The action for the app entering a phase (`device background|foreground`), or `null` for none. */
    public val scenePhase: ((AgentDevice.Phase) -> A?)? = null,
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
    public val surface: AgentDevice.Surface
        get() {
            val subcommands = mutableListOf<AgentDevice.Surface.Subcommand>()
            if (permissions.isNotEmpty()) subcommands += listOf(AgentDevice.Surface.Subcommand.PERMISSION, AgentDevice.Surface.Subcommand.PROMPT)
            if (openUrl != null) subcommands += AgentDevice.Surface.Subcommand.OPEN_URL
            if (pushes.isNotEmpty()) subcommands += AgentDevice.Surface.Subcommand.PUSH
            if (scenePhase != null) subcommands += listOf(AgentDevice.Surface.Subcommand.BACKGROUND, AgentDevice.Surface.Subcommand.FOREGROUND)
            if (relaunch) subcommands += AgentDevice.Surface.Subcommand.RELAUNCH
            if (offlineErrorCode != null) subcommands += AgentDevice.Surface.Subcommand.NETWORK
            if (location) subcommands += AgentDevice.Surface.Subcommand.LOCATION
            if (biometry) subcommands += AgentDevice.Surface.Subcommand.BIOMETRY
            return AgentDevice.Surface(
                subcommands = subcommands,
                services = permissions,
                pushes = pushes.map { AgentDevice.Surface.Push(it.name, it.help) },
                offlineErrorCode = offlineErrorCode,
            )
        }

    public companion object {
        /** No device capability: no `device` command. */
        public fun <A> none(): AgentDeviceConfig<A> = AgentDeviceConfig()
    }
}
