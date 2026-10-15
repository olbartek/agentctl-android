package io.github.olbartek.agentctl.tests.host

// A host's own types, named as apps commonly name them (nzozopole has its own PermissionStatus). The library's
// device types are nested in AgentDevice, so these never clash with it: see PublicNamesTest.

enum class PermissionStatus { GRANTED, DENIED, NOT_DETERMINED }

enum class BiometryResult { OK, CANCELLED }

enum class Phase { ACTIVE, INACTIVE }

data class Location(val latitude: Double, val longitude: Double)

data class Surface(val name: String)

data class Push(val title: String)

data class Intent(val name: String)

// And the 0.7 seam's old names, which an app could well have: the library's are Agent-prefixed.

fun interface AppIntent { fun run() }

fun interface ScreenIntent { fun run() }

class StoreDriver

class ViewModelDriver

fun interface AppFactory { fun make(): String }

/** A host's own declarations sit next to a star import of the library: its own package wins, and it compiles. */
fun hostStatus(device: io.github.olbartek.agentctl.AgentDevice): PermissionStatus =
    if (device.permission("camera") == io.github.olbartek.agentctl.AgentDevice.PermissionStatus.GRANTED) {
        PermissionStatus.GRANTED
    } else {
        PermissionStatus.DENIED
    }
