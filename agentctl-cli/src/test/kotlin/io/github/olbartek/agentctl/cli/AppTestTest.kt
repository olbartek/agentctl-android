package io.github.olbartek.agentctl.cli

import io.github.olbartek.agentctl.AppTestSkip
import io.github.olbartek.agentctl.ScriptLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What this guards: `app test`'s own logic (the reference's issue #2) — which files it skips, how it reads a run
 * through the bridge into PASS, FAIL or SKIP, and the lines it prints.
 *
 * What it does not guard: launching the app and talking to its bridge, which need a device.
 */
class AppTestTest {
    @Test
    fun aSkipMarkerGivesItsReason() {
        assertEquals("checks the launch's calls", AppTestSkip.reason("# app-test: skip checks the launch's calls\nexpect screen=items"))
        assertEquals("the date is fixed only headlessly", AppTestSkip.reason("open 2\n  #  app-test:  skip   the date is fixed only headlessly"))
        assertEquals("from the old script", AppTestSkip.reason("# appctl-sim: skip from the old script"))
        assertEquals("no reason given", AppTestSkip.reason("# app-test: skip"))
    }

    @Test
    fun noMarkerNoSkip() {
        assertNull(AppTestSkip.reason("# Logging in with a code.\nopen 2"))
        assertNull(AppTestSkip.reason("# app-test: skipping ahead"))
        assertNull(AppTestSkip.reason("open 2 # app-test: skip not a comment line"))
    }

    private val lines = listOf(
        ScriptLine(line = 2, name = "open", argument = "2"),
        ScriptLine(line = 3, name = "save", argument = null),
        ScriptLine(line = 5, name = "expect", argument = "saved=false"),
    )

    @Test
    fun aRunThatExitsZeroPassesWithItsStepCount() {
        val body = """
            > open 2
              screen=items/2 title="Second item" saved=false cooldown=0
            > save
              screen=items/2 title="Second item" saved=true cooldown=3 pending=1

        """.trimIndent()
        val result = AppTest.result("save", lines, body, exitCode = 0, duration = 1234.milliseconds)
        assertEquals("PASS save (2 steps, 1234 ms)", result.report)
    }

    @Test
    fun aFailureNamesTheScriptLineAndShowsTheFailingStep() {
        val body = """
            > open 2
              screen=items/2 title="Second item" saved=false cooldown=0
            > save
              screen=items/2 title="Second item" saved=true cooldown=3 pending=1
            > expect saved=false
              screen=items/2 title="Second item" saved=true cooldown=3 pending=1
              FAIL expected saved=false, got saved=true

        """.trimIndent()
        val result = AppTest.result("save", lines, body, exitCode = 1, duration = 2.seconds)
        assertTrue(result.outcome is AppTest.Outcome.Failed)
        assertEquals(
            """
            FAIL save:5
              > expect saved=false
                screen=items/2 title="Second item" saved=true cooldown=3 pending=1
                FAIL expected saved=false, got saved=true
            """.trimIndent(),
            result.report,
        )
    }

    @Test
    fun aParseErrorFromTheBridgeFails() {
        val result = AppTest.result(
            "broken",
            lines,
            "error: parse error: line 1, column 8: unterminated quote\n",
            exitCode = 2,
            duration = Duration.ZERO,
        )
        assertEquals("FAIL broken\n  error: parse error: line 1, column 8: unterminated quote", result.report)
    }

    @Test
    fun theSummaryCountsSkipsApart() {
        val results = listOf(
            AppTest.Result("a", AppTest.Outcome.Passed(3, 1.seconds)),
            AppTest.Result("b", AppTest.Outcome.Failed(2, "> x")),
            AppTest.Result("c", AppTest.Outcome.Skipped("real time")),
            AppTest.Result("d", AppTest.Outcome.Broken("launch failed")),
        )
        assertEquals("1 passed, 2 failed, 1 skipped", AppTest.summary(results))
        assertEquals("SKIP c: real time", results[2].report)
        assertEquals("FAIL d\n  launch failed", results[3].report)
    }

    @Test
    fun chapterTimestamps() {
        assertEquals("00:00:00", AppTest.timestamp(Duration.ZERO))
        assertEquals("00:01:01", AppTest.timestamp(61_900.milliseconds))
        assertEquals("03:25:07", AppTest.timestamp((3 * 3600 + 25 * 60 + 7).seconds))
    }
}
