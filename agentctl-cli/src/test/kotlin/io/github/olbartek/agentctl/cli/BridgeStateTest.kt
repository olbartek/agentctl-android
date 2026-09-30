package io.github.olbartek.agentctl.cli

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * What this guards: the launch state (`<outputPath>/bridge.json`) that `app launch` writes and the other `app`
 * commands read, byte for byte as agentctl-ios writes it, and which port each command uses.
 */
class BridgeStateTest {
    private val state = BridgeState(
        platform = "android",
        device = "emulator-5556",
        port = 8766,
        appId = "io.github.olbartek.agentctl.examples.agentshop",
        launchedAt = Instant.parse("2026-09-30T10:00:00Z"),
    )

    @Test
    fun rendersLikeFoundationsEncoderWithOneTrailingNewline() {
        assertEquals(
            """
            {
              "appId" : "io.github.olbartek.agentctl.examples.agentshop",
              "device" : "emulator-5556",
              "launchedAt" : "2026-09-30T10:00:00Z",
              "platform" : "android",
              "port" : 8766
            }
            """.trimIndent() + "\n",
            state.render(),
        )
    }

    @Test
    fun launchedAtHasWholeSeconds() {
        val later = state.copy(launchedAt = Instant.parse("2026-09-30T10:00:00.987654Z"))
        assertEquals(true, later.render().contains("\"launchedAt\" : \"2026-09-30T10:00:00Z\""))
    }

    @Test
    fun readsWhatItWrites() {
        assertEquals(state, BridgeState.parse(state.render()))
    }

    @Test
    fun readsCompactOrReorderedJson() {
        val text = """{"port":8765,"platform":"ios","launchedAt":"2026-09-30T10:00:00Z","device":"ABC","appId":"dev.app"}"""
        assertEquals(BridgeState("ios", "ABC", 8765, "dev.app", Instant.parse("2026-09-30T10:00:00Z")), BridgeState.parse(text))
    }

    @Test
    fun unreadableStateIsNoState() {
        assertNull(BridgeState.parse(""))
        assertNull(BridgeState.parse("{"))
        assertNull(BridgeState.parse("""{"port":"x"}"""))
        assertNull(BridgeState.parse("""{"platform":"android"}"""))
    }

    @Test
    fun savesAndLoadsInTheOutputDirectory() {
        val root = Files.createTempDirectory("bridge-state").toFile()
        val layout = Layout(root, ".appctl")
        assertNull(BridgeState.load(layout))
        state.save(layout)
        assertEquals(File(root, ".appctl/bridge.json"), BridgeState.file(layout))
        assertEquals(state, BridgeState.load(layout))
    }

    @Test
    fun aClientsPortComesFromTheFlagThenTheEnvironmentThenTheStateThenTheDefault() {
        assertEquals(Ports.Resolved(9000, Ports.Source.FLAG), Ports.client(9000, mapOf("APPCTL_PORT" to "9001")) { state })
        assertEquals(Ports.Resolved(9001, Ports.Source.ENVIRONMENT), Ports.client(null, mapOf("APPCTL_PORT" to "9001")) { state })
        assertEquals(Ports.Resolved(8766, Ports.Source.STATE, state), Ports.client(null, emptyMap()) { state })
        assertEquals(Ports.Resolved(8765, Ports.Source.DEFAULT), Ports.client(null, emptyMap()) { null })
    }

    @Test
    fun theStateIsOnlyReadWhenNeeded() {
        Ports.client(9000, emptyMap()) { error("read with --port") }
        Ports.client(null, mapOf("APPCTL_PORT" to "9001")) { error("read with APPCTL_PORT") }
    }

    @Test
    fun anUnreadableStateFileIsAnError() {
        val root = Files.createTempDirectory("bridge-state").toFile()
        val layout = Layout(root, ".appctl")
        BridgeState.file(layout).apply { parentFile.mkdirs() }.writeText("{\"port\": 8765}")
        assertFailsWith<Unreadable> { BridgeState.load(layout) }
    }

    @Test
    fun anEmptyEnvironmentValueIsUnset() {
        assertEquals(Ports.Resolved(8766, Ports.Source.STATE, state), Ports.client(null, mapOf("APPCTL_PORT" to "")) { state })
    }

    @Test
    fun aMalformedEnvironmentPortIsAUsageError() {
        for (value in listOf("abc", "0", "65536", "-1", "80 80")) {
            assertFailsWith<Ports.BadEnvironmentPort> { Ports.client(null, mapOf("APPCTL_PORT" to value)) { null } }
        }
    }

    @Test
    fun aLaunchUsesAnExplicitPortExactly() {
        assertEquals(9000, Ports.explicit(9000, mapOf("APPCTL_PORT" to "9001")))
        assertEquals(9001, Ports.explicit(null, mapOf("APPCTL_PORT" to "9001")))
        assertNull(Ports.explicit(null, emptyMap()))
    }

    @Test
    fun aLaunchScansFromTheDefaultForTheFirstFreePort() {
        assertEquals(8765, Ports.firstFree { true })
        assertEquals(8767, Ports.firstFree { it >= 8767 })
        assertEquals(8864, Ports.firstFree { it == 8864 })
        assertNull(Ports.firstFree { false })
        assertNull(Ports.firstFree { it == 8865 })
    }

    @Test
    fun aLaunchAnsweredByAnotherAppSaysWhichOrThatItDidNotSay() {
        assertEquals(
            "the app's agent bridge on 127.0.0.1:8765 answers as dev.other, not dev.app: another app holds that port; " +
                "pass --port or set APPCTL_PORT",
            Message.anotherApp(8765, "dev.other", "dev.app"),
        )
        // The app just launched always names itself: silence is another app, or an installed one from before the header.
        assertEquals(
            "the app's agent bridge on 127.0.0.1:8765 answers as an app without X-Appctl-App, not dev.app: another app " +
                "holds that port; pass --port or set APPCTL_PORT (or the installed app predates X-Appctl-App: launch without --no-build)",
            Message.anotherApp(8765, null, "dev.app"),
        )
    }

    @Test
    fun aBridgeThatCouldNotListenSaysAnotherProcessHoldsThePort() {
        assertEquals(
            "the app's agent bridge could not listen on 127.0.0.1:8766: another process holds that port; pass --port or set APPCTL_PORT",
            Message.couldNotListen(8766),
        )
    }

    @Test
    fun aRetryScansAboveThePortThatWasTaken() {
        assertEquals(8768, Ports.firstFree(after = 8767) { true })
        assertNull(Ports.firstFree(after = 8864) { true })
    }

    @Test
    fun readsTheListeningPortsFromProcNetTcp() {
        // /proc/net/tcp and tcp6: local address `ip:port` in hex, state 0A = LISTEN. 0x223D = 8765, 0x223E = 8766.
        val tcp = """
              sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 0100007F:223D 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123        0 1 1 0000000000000000 100 0 0 10 0
               1: 0100007F:223E 0100007F:A1B2 01 00000000:00000000 00:00000000 00000000 10123        0 2 1 0000000000000000 20 4 30 10 -1
        """.trimIndent()
        val tcp6 = """
              sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 00000000000000000000000000000000:1F90 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 3 1 0000000000000000 100 0 0 10 0
        """.trimIndent()
        assertEquals(setOf(8765, 8080), Ports.listening(tcp + "\n" + tcp6))
    }

    private val forwardList = """
        emulator-5554 tcp:8765 tcp:8765
        emulator-5556 tcp:8766 tcp:8766
        emulator-5556 tcp:8770 tcp:9000
        emulator-5556 tcp:8780 localabstract:chrome_devtools_remote
    """.trimIndent()

    @Test
    fun readsTcpForwards() {
        assertEquals(
            listOf(Ports.Forward("emulator-5554", 8765, 8765), Ports.Forward("emulator-5556", 8766, 8766), Ports.Forward("emulator-5556", 8770, 9000)),
            Ports.forwards(forwardList),
        )
    }

    @Test
    fun onlyThisAppsOwnForwardOnThisDeviceIsEverRemoved() {
        val mine = state.copy(device = "emulator-5556", port = 8766)
        // Its app has quit: nothing listens behind it.
        assertEquals(8766, Ports.ownStaleForward(mine, "emulator-5556", mine.appId, forwardList, listening = emptySet()))
        // Still running (a second launch of it, say): kept.
        assertNull(Ports.ownStaleForward(mine, "emulator-5556", mine.appId, forwardList, listening = setOf(8766)))
        // Another app's record, another device's, or no record: nothing is removed.
        assertNull(Ports.ownStaleForward(mine, "emulator-5556", "dev.other", forwardList, emptySet()))
        assertNull(Ports.ownStaleForward(mine, "emulator-5554", mine.appId, forwardList, emptySet()))
        assertNull(Ports.ownStaleForward(null, "emulator-5556", mine.appId, forwardList, emptySet()))
        // A forward to another device port (8770 → 9000) is judged by its device port.
        assertNull(Ports.ownStaleForward(mine.copy(port = 8770), "emulator-5556", mine.appId, forwardList, setOf(9000)))
    }

    @Test
    fun aPortSomethingAnswersOnIsNotFreeEvenIfItCouldBeBoundBeside() {
        // A wildcard listener, as an iOS simulator's bridge may be: a reusing bind on 127.0.0.1 can succeed beside it.
        java.net.ServerSocket().use { wildcard ->
            wildcard.bind(java.net.InetSocketAddress(0))
            assertEquals(false, AppLauncher.freeOnHost(wildcard.localPort))
        }
        val free = java.net.ServerSocket(0).use { it.localPort }
        assertEquals(true, AppLauncher.freeOnHost(free))
    }
}
