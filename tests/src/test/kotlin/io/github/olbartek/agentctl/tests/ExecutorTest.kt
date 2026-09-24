package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.StepFormatter
import io.github.olbartek.agentctl.StepRecord
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.runtime.RunResult
import io.github.olbartek.agentctl.runtime.RunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What this guards: the runner's dispatch and reporting — how a script is executed against a real app, which
 * failures are usage errors rather than assertion failures, and that two ways of reaching the same state produce
 * the same state. It runs against the example app, so every command and mock method here is TinyApp's own.
 */
class ExecutorTest {
    private fun run(script: String): Pair<StepRecord, RunResult> = runBlocking {
        val runner = TinyAppConfig.headless().makeRunner()
        val launch = runner.launch()
        launch.step to runner.run(script)
    }

    @Test
    fun launchSettlesOnTheFirstScreen() {
        val (launch, result) = run("expect screen=items call=items.fetch pending=0")
        assertEquals("(launch)", launch.command)
        assertEquals(listOf("items.fetch"), launch.calls)
        assertTrue(launch.settled && launch.ok)
        assertEquals(RunStatus.OK, result.status)
    }

    @Test
    fun parseErrorsAreUsageErrors() {
        val (_, result) = run("expect \"open")
        assertEquals(RunStatus.USAGE, result.status)
        assertEquals("parse error: line 1, column 8: unterminated quote", result.message)
        assertTrue(result.steps.isEmpty())
    }

    @Test
    fun unknownCommandsListTheValidOnes() {
        val (_, result) = run("frobnicate")
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(
            "unknown command 'frobnicate' on items. Valid here: open <id>, refresh, retry, expect, advance, mock",
            result.steps.last().message,
        )
    }

    @Test
    fun failedExpectationsStopTheScript() {
        val (_, result) = run("expect screen=nope; expect screen=items")
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(1, result.steps.size)
        assertEquals("expected screen=nope, got screen=items", result.steps[0].message)
        assertEquals(1, result.failedLine?.line)
    }

    @Test
    fun malformedExpectIsAUsageError() {
        assertEquals(RunStatus.USAGE, run("expect screen").second.status)
        assertEquals("expected key=value, got 'screen'", run("expect screen").second.steps.last().message)
        assertEquals("expect needs at least one key=value pair", run("expect").second.steps.last().message)
    }

    @Test
    fun mockValidation() {
        assertEquals(RunStatus.OK, run("mock items.fetch network").second.status)
        val count = run("mock items.fetch").second
        assertEquals(RunStatus.USAGE, count.status)
        assertEquals("usage: mock <client.method> <error> — exactly two words; mockable: items.fetch", count.steps.last().message)
        val method = run("mock items.nope network").second
        assertEquals(RunStatus.FAILED, method.status)
        assertEquals("unknown mock method 'items.nope'; mockable: items.fetch", method.steps.last().message)
        val code = run("mock items.fetch notFound").second
        assertEquals(RunStatus.FAILED, code.status)
        assertEquals("unknown error 'notFound' for items.fetch; valid: network, timeout", code.steps.last().message)
    }

    @Test
    fun advanceValidation() {
        assertEquals(RunStatus.OK, run("advance 5m").second.status)
        assertEquals(RunStatus.USAGE, run("advance soon").second.status)
        assertEquals(RunStatus.USAGE, run("advance").second.status)
        val overflow = run("advance 9999999999999999h").second
        assertEquals(RunStatus.USAGE, overflow.status)
        assertEquals("advance needs a duration such as 500ms, 30s, 5m or 1h", overflow.steps.last().message)
        // Each duration fits, but together they would pass the limit: the second is refused before it moves.
        val cumulative = run("advance ${Long.MAX_VALUE}s; advance 1s").second
        assertEquals(RunStatus.USAGE, cumulative.status)
        assertEquals(2, cumulative.steps.size)
        assertEquals("advance would take the clock past ${Long.MAX_VALUE} seconds", cumulative.steps.last().message)
    }

    @Test
    fun gatedCommandsAreRefusedWithTheirHint() {
        val refused = run("retry").second
        assertEquals(RunStatus.FAILED, refused.status)
        assertEquals("retry is disabled here (error=none)", refused.steps.last().message)
        assertEquals(emptyList(), refused.steps.last().calls)
        assertEquals(RunStatus.OK, run("mock items.fetch network; refresh; retry; expect error=none items=3").second.status)
    }

    /** The fetch at launch is the only one a script cannot reach, so this makes it fail before launching. */
    @Test
    fun openIsRefusedWhileTheListIsEmpty() = runBlocking {
        val app = TinyAppConfig.headless()
        app.faults.set("items.fetch", "network")
        val runner = app.makeRunner()
        val launch = runner.launch().step
        val result = runner.run("open 2")
        assertTrue(SummaryItem("items", 0) in launch.summary)
        assertEquals("network", launch.error)
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("open is disabled here (items=0)", result.steps.last().message)
    }

    @Test
    fun argumentErrorsNameTheUsage() {
        assertEquals("open <id>: invalid argument: expected an item id such as 2", run("open x").second.steps.last().message)
        assertEquals("open <id>: missing argument: expected <id>", run("open").second.steps.last().message)
        assertEquals(
            "save: unexpected argument 'now': this command takes none",
            run("open 2; save now").second.steps.last().message,
        )
        // A quoted argument is the same argument.
        assertEquals(RunStatus.OK, run("open \"2\"; expect screen=items/2").second.status)
    }

    @Test
    fun commandsOfAnotherScreenAreRefused() {
        val result = run("save").second
        assertEquals(RunStatus.FAILED, result.status)
        assertTrue(result.steps.last().message!!.startsWith("unknown command 'save' on items"))
    }

    @Test
    fun unknownIdsReportAnError() {
        val result = run("open 99; expect error=notFound screen=items items=3").second
        assertEquals(RunStatus.OK, result.status)
        assertEquals("notFound", result.steps.first().error)
        assertEquals(RunStatus.OK, run("open 99; open 2; expect screen=items/2 error=none").second.status)
    }

    @Test
    fun mockClearsTheCallsAnExpectSees() {
        val result = run("mock items.fetch network; expect call=items.fetch").second
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("expected call=items.fetch, got calls=none", result.steps.last().message)
    }

    @Test
    fun sessionReplayMatchesASingleRun() = runBlocking {
        val a = TinyAppConfig.headless().makeRunner()
        a.launch()
        a.run("refresh")
        a.run("advance 1s")
        val b = TinyAppConfig.headless().makeRunner()
        b.launch()
        b.run("refresh; advance 1s")
        assertEquals(a.stateDump, b.stateDump)
    }

    @Test
    fun splittingAScriptDoesNotChangeItsSteps() = runBlocking {
        val a = TinyAppConfig.headless().makeRunner()
        a.launch()
        val opened = a.run("open 2")
        val saved = a.run("save")
        val b = TinyAppConfig.headless().makeRunner()
        b.launch()
        val whole = b.run("open 2; save")
        assertEquals(StepFormatter.text(whole.steps), StepFormatter.text(opened.steps + saved.steps))
    }

    /** Two pushes of the same screen are two appearances, and popping one cancels its own countdown only. */
    @Test
    fun stackElementsKeepTheirOwnEffects() {
        val result = run(
            "open 2; save; expect pending=1; back; expect screen=items pending=0; open 2; expect cooldown=0 saved=false pending=0",
        ).second
        assertEquals(RunStatus.OK, result.status, StepFormatter.text(result.steps))
    }
}
