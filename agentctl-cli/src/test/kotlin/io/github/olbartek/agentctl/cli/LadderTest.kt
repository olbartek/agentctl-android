package io.github.olbartek.agentctl.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class LadderTest {
    @Test
    fun rows() {
        assertEquals("1 task", Ladder.count(1, "task"))
        assertEquals("6 tests", Ladder.count(6, "test"))
        assertEquals("L2 scenarios  ok    3/3 scenarios                0.1s", Ladder.row("L2 scenarios", true, "3/3 scenarios", 0.07))
        // Padded to line up, never truncated.
        val long = "x".repeat(40)
        assertEquals("L4 app        FAIL  $long 12.0s", Ladder.row("L4 app", false, long, 12.0))
    }

    @Test
    fun adbShellQuoting() {
        assertEquals("'open 2; save'", AppLauncher.shellQuoted("open 2; save"))
        assertEquals("'it'\\''s'", AppLauncher.shellQuoted("it's"))
    }
}
