package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.*
import io.github.olbartek.agentctl.tests.host.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What this guards: a host that declares its own `PermissionStatus`, `Location`, `Phase`, `Push`… and star-imports
 * both its package and the library's still compiles, because the library's device types are nested in [AgentDevice]
 * (nzozopole-ios hit the clash with top-level ones). Compiling this file is the test.
 */
class PublicNamesTest {
    /** A host's fake maps the library's status onto its own, in one file, with no alias. */
    private fun hostStatus(status: AgentDevice.PermissionStatus): PermissionStatus = when (status) {
        AgentDevice.PermissionStatus.GRANTED, AgentDevice.PermissionStatus.LIMITED -> PermissionStatus.GRANTED
        AgentDevice.PermissionStatus.DENIED -> PermissionStatus.DENIED
        AgentDevice.PermissionStatus.NOT_DETERMINED -> PermissionStatus.NOT_DETERMINED
    }

    @Test
    fun aHostsOwnDeviceNamesSitBesideTheLibrarysUnderStarImports() {
        val device = AgentDevice()
        device.setPermission("camera", AgentDevice.PermissionStatus.DENIED)
        assertEquals(PermissionStatus.DENIED, hostStatus(device.permission("camera")))
        device.setLocation(AgentDevice.Location(52.0, 21.0))
        assertEquals(Location(52.0, 21.0), device.location?.let { Location(it.latitude, it.longitude) })
        device.setPhase(AgentDevice.Phase.BACKGROUND)
        assertEquals(Phase.INACTIVE, if (device.phase == AgentDevice.Phase.BACKGROUND) Phase.INACTIVE else Phase.ACTIVE)
        assertEquals(BiometryResult.OK, if (AgentDevice.BiometryResult.SUCCESS.name == "SUCCESS") BiometryResult.OK else BiometryResult.CANCELLED)
        val config = AgentDeviceConfig(pushes = listOf(AgentDevice.Push("shipped", help = "Shipped.", opened = Push("open"))))
        assertEquals(listOf(AgentDevice.Surface.Push("shipped", "Shipped.")), config.surface.pushes)
        assertEquals(Surface("app"), Surface("app"))
        assertEquals(PermissionStatus.DENIED, io.github.olbartek.agentctl.tests.host.hostStatus(device))
    }
}
