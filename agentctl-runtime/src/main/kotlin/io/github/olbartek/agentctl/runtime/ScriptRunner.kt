package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.AgentCommandException
import io.github.olbartek.agentctl.AgentContainer
import io.github.olbartek.agentctl.AgentDuration
import io.github.olbartek.agentctl.AgentRegistry
import io.github.olbartek.agentctl.AgentStore
import io.github.olbartek.agentctl.ArgumentText
import io.github.olbartek.agentctl.Expectation
import io.github.olbartek.agentctl.ExpectationSyntaxError
import io.github.olbartek.agentctl.MockCallLog
import io.github.olbartek.agentctl.MockFaults
import io.github.olbartek.agentctl.MockMethod
import io.github.olbartek.agentctl.ScriptError
import io.github.olbartek.agentctl.ScriptLine
import io.github.olbartek.agentctl.ScriptParser
import io.github.olbartek.agentctl.StepRecord

/** How a [ScriptRunner] waits for the app and whether it can control time. */
public class RunnerEnvironment(
    public val settle: suspend () -> SettleResult,
    /** Advances the virtual clock. `null` in the running app, where `advance` is not available. */
    public val advance: (suspend (AgentDuration) -> Unit)?,
    /** Headless runs send each screen's `onAppear` action when it becomes active (no views exist to do it). */
    public val synthesizesAppearance: Boolean,
)

/** The exit codes of CONTRACT.md §5. */
public enum class RunStatus(public val code: Int) {
    OK(0),

    /** A command or `expect` failed. */
    FAILED(1),

    /** A usage or parse error. */
    USAGE(2),
    INTERNAL_ERROR(3),
}

public data class RunResult(
    val steps: List<StepRecord>,
    val status: RunStatus,
    /** The lines that ran successfully, for `--session` files. */
    val executed: List<ScriptLine>,
    /** The line that failed, if any. */
    val failedLine: ScriptLine? = null,
    /** A failure that has no step, such as a script syntax error. */
    val message: String? = null,
)

/** A step and the exit code it earned. */
public data class StepOutcome(val step: StepRecord, val status: RunStatus)

/**
 * Runs script commands against an [AgentStore], headlessly (the CLI) or in the app (the bridge). Call it from
 * the store's own thread: the calling thread headlessly, the main thread in the app.
 */
public class ScriptRunner<S, A>(
    public val store: AgentStore<S, A>,
    private val container: AgentContainer<S, A>,
    private val callLog: MockCallLog,
    private val faults: MockFaults,
    private val pending: () -> Int,
    private val environment: RunnerEnvironment,
    /** The methods `mock` accepts, from the host that built this runner. */
    private val mockMethods: List<MockMethod>,
) {
    /** Adds a diff of the root state to each step (`--diff`). */
    public var recordsDiff: Boolean = false

    private var lastIdentity: String? = null
    private var lastCalls: List<String> = emptyList()

    /** How far `advance` has moved the clock so far. */
    private var advanced: AgentDuration = AgentDuration.ZERO

    public val state: S get() = store.state

    /** The root state, rendered for `state` and `GET /state`. */
    public val stateDump: String get() = StateDump.render(state)

    /**
     * Settles the initial state (sending the first screen's appearance) and returns the `(launch)` step with its
     * status. The status is [RunStatus.FAILED] when the app did not settle; a caller must then not run a script
     * (CONTRACT.md §3.4).
     */
    public suspend fun launch(): StepOutcome {
        val start = callLog.count
        val before = state
        val settle = settleAndAppear()
        return finish("(launch)", start, settle, before)
    }

    /** Parses and runs a script, stopping at the first failure. */
    public suspend fun run(source: String): RunResult {
        val lines = try {
            ScriptParser.parse(source)
        } catch (error: ScriptError) {
            return RunResult(emptyList(), RunStatus.USAGE, emptyList(), message = "parse error: ${error.description}")
        }
        return run(lines)
    }

    public suspend fun run(lines: List<ScriptLine>): RunResult {
        val steps = mutableListOf<StepRecord>()
        val executed = mutableListOf<ScriptLine>()
        for (line in lines) {
            val (step, status) = execute(line)
            steps.add(step)
            if (status != RunStatus.OK) return RunResult(steps, status, executed, failedLine = line)
            executed.add(line)
        }
        return RunResult(steps, RunStatus.OK, executed)
    }

    public suspend fun execute(line: ScriptLine): StepOutcome = when (line.name) {
        "expect" -> expect(line)
        "advance" -> advance(line)
        "mock" -> mock(line)
        else -> command(line)
    }

    /** The current screen summary as a step, without running anything. */
    public fun snapshot(command: String): StepRecord = record(command, lastCalls, SettleResult(true, pending()))

    // Commands

    private fun expect(line: ScriptLine): StepOutcome {
        val expectation = try {
            Expectation.parse(line.argument)
        } catch (error: ExpectationSyntaxError) {
            return fail(line, RunStatus.USAGE, error.message ?: "")
        }
        val step = snapshot(line.text)
        val failures = expectation.evaluate(step.snapshot)
        if (failures.isNotEmpty()) {
            return StepOutcome(step.copy(ok = false, message = failures.joinToString("\n")), RunStatus.FAILED)
        }
        return StepOutcome(step, RunStatus.OK)
    }

    private suspend fun advance(line: ScriptLine): StepOutcome {
        val argument = line.argument
        val duration = argument?.let { ScriptParser.parseDuration(ArgumentText.unquoted(it)) }
            ?: return fail(line, RunStatus.USAGE, "advance needs a duration such as 500ms, 30s, 5m or 1h")
        val advanceClock = environment.advance
            ?: return fail(line, RunStatus.USAGE, "advance is only available headlessly, not in the running app")
        if (duration > ADVANCE_LIMIT - advanced) {
            return fail(line, RunStatus.USAGE, "advance would take the clock past ${Long.MAX_VALUE} seconds")
        }
        advanced += duration
        val start = callLog.count
        val before = state
        advanceClock(duration)
        val settle = settleAndAppear()
        return finish(line.text, start, settle, before)
    }

    private fun mock(line: ScriptLine): StepOutcome {
        val tokens = ArgumentText.tokens(line.argument ?: "")
        val names = mockMethods.joinToString(", ") { it.name }
        if (tokens.size != 2) {
            val mockable = if (names.isEmpty()) "" else "; mockable: $names"
            return fail(line, RunStatus.USAGE, "usage: mock <client.method> <error> — exactly two words$mockable")
        }
        val (name, code) = tokens
        val method = mockMethods.firstOrNull { it.name == name }
            ?: return fail(line, RunStatus.FAILED, "unknown mock method '$name'; mockable: $names")
        if (code !in method.errorCodes) {
            return fail(line, RunStatus.FAILED, "unknown error '$code' for $name; valid: ${method.errorCodes.joinToString(", ")}")
        }
        faults.set(name, code)
        lastCalls = emptyList()
        return StepOutcome(snapshot(line.text), RunStatus.OK)
    }

    private suspend fun command(line: ScriptLine): StepOutcome {
        val screen = container.activeScreen(state)
        val command = screen.command(line.name)
        if (command == null) {
            val valid = (screen.commands.map { it.usage } + AgentRegistry.runtimeCommandNames).joinToString(", ")
            return fail(line, RunStatus.FAILED, "unknown command '${line.name}' on ${screen.path}. Valid here: $valid")
        }
        command.disabledReason?.let { reason ->
            return fail(line, RunStatus.FAILED, "${command.name} is disabled here ($reason)")
        }
        val action = try {
            command.makeAction(line.argument?.let(ArgumentText::unquoted))
        } catch (error: AgentCommandException) {
            return fail(line, RunStatus.FAILED, "${command.usage}: ${error.error.message}")
        }
        val start = callLog.count
        val before = state
        store.send(action)
        val settle = settleAndAppear()
        return finish(line.text, start, settle, before)
    }

    // Helpers

    /** Settles; headlessly, also sends `onAppear` for each newly active screen until the screen is stable. */
    private suspend fun settleAndAppear(): SettleResult {
        var result = environment.settle()
        repeat(10) {
            val screen = container.activeScreen(state)
            if (screen.identity == lastIdentity) return result
            lastIdentity = screen.identity
            val appear = screen.appearAction
            if (!environment.synthesizesAppearance || appear == null) return@repeat
            store.send(appear)
            result = environment.settle()
        }
        return result
    }

    private fun finish(command: String, callStart: Int, settle: SettleResult, before: S): StepOutcome {
        val calls = callLog.since(callStart)
        lastCalls = calls
        var step = record(command, calls, settle)
        if (recordsDiff) step = step.copy(diff = StateDump.diff(before, state))
        if (!settle.settled) {
            return StepOutcome(
                step.copy(ok = false, message = "did not settle within the time limit (a real-time dependency may have leaked in)"),
                RunStatus.FAILED,
            )
        }
        return StepOutcome(step, RunStatus.OK)
    }

    private fun record(command: String, calls: List<String>, settle: SettleResult): StepRecord {
        val screen = container.activeScreen(state)
        return StepRecord(
            command = command,
            screen = screen.path,
            summary = screen.summary,
            calls = calls,
            error = screen.errorCode,
            pending = settle.pending,
            settled = settle.settled,
        )
    }

    private fun fail(line: ScriptLine, status: RunStatus, message: String): StepOutcome {
        val step = record(line.text, emptyList(), SettleResult(true, pending()))
        return StepOutcome(step.copy(ok = false, message = message), status)
    }

    public companion object {
        /**
         * The furthest `advance` moves the clock in one run, in total: `Long.MAX_VALUE` seconds, the reference's
         * limit, so the same script is refused at the same line. The virtual clock itself counts milliseconds and
         * saturates long before that, which no app can observe.
         */
        public val ADVANCE_LIMIT: AgentDuration = AgentDuration.ofSeconds(Long.MAX_VALUE)
    }
}
