package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.AgentDevice
import io.github.olbartek.agentctl.tests.host.Location
import io.github.olbartek.agentctl.tests.host.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** The same with explicit imports: the host's names and [AgentDevice] import side by side, with no alias. */
class PublicNamesExplicitTest {
    @Test
    fun aHostsOwnDeviceNamesSitBesideTheLibrarysUnderExplicitImports() {
        val device = AgentDevice()
        val status = if (device.permission("camera") == AgentDevice.PermissionStatus.NOT_DETERMINED) PermissionStatus.NOT_DETERMINED else PermissionStatus.GRANTED
        assertEquals(PermissionStatus.NOT_DETERMINED, status)
        assertEquals(null, device.location?.let { Location(it.latitude, it.longitude) })
    }
}
