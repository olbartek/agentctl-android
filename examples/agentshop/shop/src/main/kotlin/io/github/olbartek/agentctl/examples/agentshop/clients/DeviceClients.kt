package io.github.olbartek.agentctl.examples.agentshop.clients

import io.github.olbartek.agentctl.AgentDevice
import io.github.olbartek.agentctl.examples.agentshop.models.Session

/**
 * Asking for permission to send notifications. In this all-mock app it is AgentCtl's simulated device
 * (`environment.device`), so a script answers the system prompt with `device prompt allow|deny`; a real app would ask
 * Android for `POST_NOTIFICATIONS` instead.
 */
class NotificationsClient(
    val requestAuthorization: suspend () -> AgentDevice.PermissionStatus = { AgentDevice.PermissionStatus.NOT_DETERMINED },
) {
    companion object {
        fun live(device: AgentDevice): NotificationsClient =
            NotificationsClient(requestAuthorization = { device.requestPermission("notifications") })
    }
}

/** The device's location and the permission to read it, through the simulated device (`device location`, `device permission location …`). */
class LocationClient(
    val requestAuthorization: suspend () -> AgentDevice.PermissionStatus = { AgentDevice.PermissionStatus.NOT_DETERMINED },
    val current: suspend () -> AgentDevice.Location? = { null },
) {
    companion object {
        fun live(device: AgentDevice): LocationClient = LocationClient(
            requestAuthorization = { device.requestPermission("location") },
            current = { device.location },
        )
    }
}

/** Face ID, through the simulated device (`device biometry …`). */
class BiometricsClient(
    val authenticate: suspend () -> AgentDevice.BiometryResult = { AgentDevice.BiometryResult.UNAVAILABLE },
) {
    companion object {
        fun live(device: AgentDevice): BiometricsClient = BiometricsClient(authenticate = { device.authenticateBiometry() })
    }
}

/**
 * The account that signs in with Face ID, as a real app would keep it in the keystore. It outlives a log-out and a
 * relaunch (`device relaunch`); a headless run starts with an empty one.
 */
class FaceIDVault {
    @Volatile
    var session: Session? = null
        private set

    fun enable(session: Session) {
        this.session = session
    }

    fun disable() {
        session = null
    }
}
