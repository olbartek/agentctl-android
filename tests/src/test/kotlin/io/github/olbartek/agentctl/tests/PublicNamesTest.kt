package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.*
import io.github.olbartek.agentctl.cli.*
import io.github.olbartek.agentctl.runtime.*
import io.github.olbartek.agentctl.testsupport.*
import io.github.olbartek.agentctl.tests.host.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each of the host's names in a type position, unqualified: what fails to compile ("Overload resolution ambiguity")
 * when an agentctl package declares the same name at its top level.
 */
private data class UsesHostNames(
    val permissionStatus: PermissionStatus? = null,
    val biometryResult: BiometryResult? = null,
    val devicePhase: DevicePhase? = null,
    val deviceLocation: DeviceLocation? = null,
    val deviceSurface: DeviceSurface? = null,
    val deviceConfig: DeviceConfig? = null,
    val device: Device? = null,
    val permission: Permission? = null,
    val biometry: Biometry? = null,
    val push: Push? = null,
    val pushNotification: PushNotification? = null,
    val location: Location? = null,
    val phase: Phase? = null,
    val surface: Surface? = null,
    val subcommand: Subcommand? = null,
    val appFactory: AppFactory? = null,
)

/**
 * What this guards: a host that declares common words of its own (`PermissionStatus`, `Location`, `Push`…) still
 * compiles beside a star import of every agentctl package on the JVM, because the library declares none of them at
 * its top level (agentctl-ios's HostNamesTests). [UsesHostNames] fails to compile on a clash; the class literals are
 * checked at run time too, since `X::class` silently picks a library class that has a companion object.
 * (`agentctl-bridge` is an Android library, out of this JVM module's reach; it declares `AgentLaunch` and
 * `AgentLaunchArguments` only.)
 */
class PublicNamesTest {
    @Test
    fun aHostsOwnNamesStayUnambiguous() {
        assertEquals(UsesHostNames(), UsesHostNames())
        val names = listOf(
            PermissionStatus::class,
            BiometryResult::class,
            DevicePhase::class,
            DeviceLocation::class,
            DeviceSurface::class,
            DeviceConfig::class,
            Device::class,
            Permission::class,
            Biometry::class,
            Push::class,
            PushNotification::class,
            Location::class,
            Phase::class,
            Surface::class,
            Subcommand::class,
            AppFactory::class,
        ).map { it.java.name }
        assertEquals(16, names.size)
        assertEquals(emptyList(), names.filterNot { it.startsWith("io.github.olbartek.agentctl.tests.host.") })
    }

    /** A host's fake maps the library's status onto its own in one file, with no import alias. */
    @Test
    fun aFakeMapsTheLibrarysStatusOntoTheHosts() {
        fun hostStatus(status: AgentDevice.PermissionStatus): PermissionStatus = when (status) {
            AgentDevice.PermissionStatus.GRANTED, AgentDevice.PermissionStatus.LIMITED -> PermissionStatus.GRANTED
            AgentDevice.PermissionStatus.DENIED -> PermissionStatus.DENIED
            AgentDevice.PermissionStatus.NOT_DETERMINED -> PermissionStatus.NOT_DETERMINED
        }
        val device = AgentDevice()
        device.setPermission("camera", AgentDevice.PermissionStatus.DENIED)
        assertEquals(PermissionStatus.DENIED, hostStatus(device.permission("camera")))
    }
}
