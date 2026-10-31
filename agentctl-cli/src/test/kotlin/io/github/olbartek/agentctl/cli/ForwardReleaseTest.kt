package io.github.olbartek.agentctl.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What this guards: `app test` and `check --ui` remove the forward their launch made once its run is over, so the next
 * run finds that port free whatever `bridge.json` records then (another checkout's run used to move to 8766). Only that
 * forward goes: never another device's, another port's, or one that now leads somewhere else. The `adb` here is a fake
 * that lists [listing] and records every other call.
 */
class ForwardReleaseTest {
    private val calls = Files.createTempFile("adb-calls", "").toFile()
    private val listingFile = Files.createTempFile("adb-forwards", "").toFile()
    private var listing = ""
    private val adb = Files.createTempFile("adb", "").toFile().apply { setExecutable(true) }

    /** [removeExits] is what the fake's `forward --remove` exits with; [listExits], its `forward --list`. */
    private fun release(serial: String, port: Int, removeExits: Int = 0, listExits: Int = 0): Boolean {
        listingFile.writeText(listing)
        adb.writeText(
            """
            #!/bin/sh
            case "${'$'}*" in
              "forward --list") cat '${listingFile.path}'; exit $listExits ;;
              *) echo "${'$'}*" >> '${calls.path}'; exit $removeExits ;;
            esac
            """.trimIndent() + "\n",
        )
        return AppLauncher.releaseForward(adb.path, serial, port) { command -> ProcessBuilder(command).inheritIO().start().waitFor() }
    }

    @AfterTest
    fun removeTheFakeAdb() {
        adb.delete()
        calls.delete()
        listingFile.delete()
    }

    @Test
    fun theForwardALaunchMadeIsRemoved() {
        listing = "emulator-5554 tcp:8765 tcp:8765\nemulator-5556 tcp:8766 tcp:8766\n"
        assertTrue(release("emulator-5556", 8766))
        assertEquals("-s emulator-5556 forward --remove tcp:8766\n", calls.readText())
    }

    @Test
    fun noOtherForwardIsEverTouched() {
        // Another device's forward on the port, one to another device port, another port's, or none at all.
        listing = "emulator-5554 tcp:8765 tcp:8765\nemulator-5556 tcp:8770 tcp:9000\nemulator-5556 tcp:8780 localabstract:chrome_devtools_remote\n"
        assertFalse(release("emulator-5556", 8765))
        assertFalse(release("emulator-5556", 8770))
        assertFalse(release("emulator-5556", 8780))
        assertFalse(release("emulator-5556", 8799))
        listing = ""
        assertFalse(release("emulator-5554", 8765))
        assertEquals("", calls.readText())
    }

    @Test
    fun aRemoveThatFailsOrAListThatCannotRunRemovesNothing() {
        listing = "emulator-5556 tcp:8766 tcp:8766\n"
        assertFalse(release("emulator-5556", 8766, removeExits = 1))
        assertFalse(release("emulator-5556", 8766, listExits = 1))
        assertEquals("-s emulator-5556 forward --remove tcp:8766\n", calls.readText())
    }
}
