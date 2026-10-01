package io.github.olbartek.agentctl.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What this guards: the line agentctl boots an AVD with (`app launch`, `app test`, `check --ui`). After an emulator
 * crash, a plain boot stops at the crash-report consent prompt and never opens its adb ports, so the boot never ends;
 * `-no-metrics -crash-report-mode never` keeps it unattended (found by nzozopole-android).
 */
class EmulatorBootTest {
    @Test
    fun anAvdIsBootedWithoutAnyPromptToWaitOn() {
        assertEquals(
            listOf(
                "/sdk/emulator/emulator", "-avd", "agentctl_pixel7_api36",
                "-no-snapshot-save", "-no-boot-anim", "-no-metrics", "-crash-report-mode", "never",
            ),
            AppLauncher.bootCommand("/sdk/emulator/emulator", "agentctl_pixel7_api36"),
        )
    }
}
