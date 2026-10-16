package io.github.olbartek.agentctl.tests

import kotlin.test.Test
import kotlin.test.assertEquals

/** What this guards: how a script's text is read through the real CLI, where CONTRACT.md §1–§2 and §5 pin it down. */
class ScriptTextTest {
    /** CONTRACT.md §2.1: an `=` only inside quotes is not a pair; the line's shape is wrong, so exit 2. */
    @Test
    fun anExpectWhoseOnlyEqualsIsQuotedIsAUsageError() {
        val result = tinyctl("run", "open 2; expect \"title=Second item\"")
        assertEquals(2, result.status, result.combined)
        assertEquals(
            "> expect \"title=Second item\"\n  screen=items/2 title=\"Second item\" saved=false cooldown=0\n" +
                "  FAIL expected key=value, got 'title=Second item'\n",
            result.out.substringAfter("cooldown=0\n"),
        )
    }
}
