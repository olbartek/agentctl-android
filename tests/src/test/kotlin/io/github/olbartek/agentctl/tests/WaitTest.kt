package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.AgentDuration
import io.github.olbartek.agentctl.AgentWaitArguments
import io.github.olbartek.agentctl.Expectation
import io.github.olbartek.agentctl.ExpectationSyntaxError
import io.github.olbartek.agentctl.ScriptParser
import io.github.olbartek.agentctl.StepFormatter
import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.runtime.RunResult
import io.github.olbartek.agentctl.runtime.RunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * What this guards: `wait` headlessly (CONTRACT.md §2.5). The virtual clock moves from one sleep's deadline to the
 * next and stops at the one that brings the match, so a wait is as deterministic as `advance`; a wait that times out
 * has moved the clock its whole duration. TinyApp's `save` starts a three-second cooldown that ticks once a second.
 */
class WaitTest {
    private fun run(script: String): RunResult = runBlocking {
        val runner = TinyAppConfig.headless().makeRunner()
        runner.launch()
        runner.run(script)
    }

    @Test
    fun aWaitStopsTheClockAtTheMatch() {
        val result = run("open 2; save; wait cooldown=1; expect pending=1; advance 1s; expect cooldown=0 pending=0")
        assertEquals(RunStatus.OK, result.status, result.steps.last().message)
        assertEquals(
            "> wait cooldown=1\n  screen=items/2 title=\"Second item\" saved=true cooldown=1 pending=1",
            StepFormatter.text(listOf(result.steps[2])),
        )
    }

    @Test
    fun aWaitThatAlreadyMatchesTakesNoTime() {
        assertEquals(RunStatus.OK, run("open 2; save; wait cooldown=3; advance 3s; expect cooldown=0").status)
    }

    @Test
    fun aTimedOutWaitFailsWithWhatItSaw() {
        val result = run("open 2; save; wait 2s cooldown=0 saved=true")
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("wait timed out after 2s\nexpected cooldown=0, got cooldown=1", result.steps.last().message)
        assertEquals("wait 2s cooldown=0 saved=true", result.failedLine?.text)
    }

    @Test
    fun aTimedOutWaitHasMovedTheClockItsWholeDuration() {
        // Nothing sleeps on the list, so the wait goes straight to its end: 90 s later on the app's clock.
        val result = run("wait 90s screen=items/2")
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("wait timed out after 90s\nexpected screen=items/2, got screen=items", result.steps.last().message)
    }

    @Test
    fun aBareWaitReturnsOnTheNextChange() {
        val result = run("open 2; save; wait; expect cooldown=2; wait; wait; expect cooldown=0 pending=0")
        assertEquals(RunStatus.OK, result.status, result.steps.last().message)
    }

    @Test
    fun aBareWaitWithNothingComingTimesOut() {
        val result = run("wait")
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("wait timed out after 10s\nnothing changed", result.steps.last().message)
    }

    @Test
    fun callMeansACallMadeDuringTheWait() {
        val result = run("wait 1m call=items.fetch")
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("wait timed out after 1m\nexpected call=items.fetch, got calls=none", result.steps.last().message)
    }

    @Test
    fun aKeyTheScreenDoesNotHaveYetIsNotAMatchYet() {
        val result = run("wait 1s cooldown=0")
        assertEquals(RunStatus.FAILED, result.status)
        assertTrue(result.steps.last().message!!.startsWith("wait timed out after 1s\nunknown key 'cooldown' on items"))
    }

    @Test
    fun malformedWaitsAreUsageErrors() {
        for (script in listOf("wait x", "wait 5s foo", "wait 1h30m", "wait \"title=a b\"")) {
            assertEquals(RunStatus.USAGE, run(script).status, script)
        }
        assertEquals(
            "usage: wait [<duration>] [key=value …]; a duration such as 500ms, 30s, 5m or 1h, got 'x'",
            run("wait x").steps.last().message,
        )
        assertEquals("expected key=value, got 'foo'", run("wait 5s foo").steps.last().message)
    }

    @Test
    fun aWaitCountsTowardTheClockLimit() {
        val result = run("advance ${Long.MAX_VALUE}s; wait 1s")
        assertEquals(RunStatus.USAGE, result.status)
        assertEquals("wait would take the clock past ${Long.MAX_VALUE} seconds", result.steps.last().message)
    }

    @Test
    fun waitIsListedAmongTheRuntimeCommands() {
        val result = run("frobnicate")
        assertTrue(result.steps.last().message!!.endsWith("expect, advance, mock, wait, device"))
    }

    @Test
    fun parsing() {
        assertEquals(AgentWaitArguments(AgentDuration.ofSeconds(10)), AgentWaitArguments.parse(null))
        assertEquals(AgentWaitArguments(AgentDuration.ofMilliseconds(500)), AgentWaitArguments.parse("500ms"))
        assertEquals(
            AgentWaitArguments(
                expectation = Expectation(listOf(Expectation.Pair("screen", "items"), Expectation.Pair("title", "Second item"))),
            ),
            AgentWaitArguments.parse("screen=items title=\"Second item\""),
        )
        assertEquals(
            AgentWaitArguments(AgentDuration.ofSeconds(30), Expectation(listOf(Expectation.Pair("screen", "items")))),
            AgentWaitArguments.parse("30s screen=items"),
        )
        assertFailsWith<ExpectationSyntaxError> { AgentWaitArguments.parse("screen=items 30s") }
        assertEquals("1500ms", ScriptParser.formatDuration(AgentDuration.ofMilliseconds(1500)))
        assertEquals("90s", ScriptParser.formatDuration(AgentDuration.ofSeconds(90)))
        assertEquals("5m", ScriptParser.formatDuration(AgentDuration.ofSeconds(300)))
        assertEquals("2h", ScriptParser.formatDuration(AgentDuration.ofSeconds(7200)))
        assertEquals("0s", ScriptParser.formatDuration(AgentDuration.ZERO))
    }
}

/**
 * What this guards: a sleep's deadline, which `wait` moves the clock to, is when the sleep really ends. `delay` rounds
 * a fraction of a millisecond up, so the deadline does too; rounded down, `wait` would step the clock by nothing.
 */
class SleepDeadlineTest {
    @Test
    fun aSleepOfAFractionOfAMillisecondEndsAtTheNextMillisecond() {
        val dispatcher = io.github.olbartek.agentctl.runtime.VirtualTimeDispatcher()
        val clock = io.github.olbartek.agentctl.CountingClock { java.time.Instant.EPOCH.plusMillis(dispatcher.currentTime) }
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        scope.launch { clock.sleep(1500.microseconds) }
        dispatcher.runDue()
        assertEquals(java.time.Instant.EPOCH.plusMillis(2), clock.earliestDeadline)
        dispatcher.advanceBy(2)
        dispatcher.runDue()
        assertEquals(null, clock.earliestDeadline)
        scope.cancel()
    }
}
