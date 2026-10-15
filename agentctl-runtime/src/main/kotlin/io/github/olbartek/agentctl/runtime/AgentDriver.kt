package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.ActiveScreen
import io.github.olbartek.agentctl.AgentEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job

/**
 * What the engine needs from an app, whatever its architecture. Everything else the runner does — parsing, `expect`,
 * `advance`, `mock`, `device`, the step output, sessions — is the engine's own and never sees the app's types
 * (CONTRACT.md holds for every driver).
 *
 * Two drivers ship: [StoreDriver] for an app on a store (AgentCtl's [io.github.olbartek.agentctl.Store], or any
 * [io.github.olbartek.agentctl.AgentStore]), and [ViewModelDriver] for MVVM: view models under an
 * [io.github.olbartek.agentctl.AgentCoordinator].
 *
 * @param A what a command, an appearance or a device event turns into. A value: commands produce it before it is
 *   sent, and the device configuration builds it without a reference to the running app, which a relaunch replaces.
 * @param App what the app's root UI shows: the store, or the coordinator. [relaunch] replaces it.
 */
public interface AgentDriver<A, out App> {
    /** What the app's root UI shows. */
    public val app: App

    /** The screen an agent is looking at: path, identity, summary, error, appearance and commands. */
    public fun activeScreen(): ActiveScreen<A>

    /** Delivers an action, synchronously, on the app's thread. The work it starts is the app's; settling waits for it. */
    public fun send(action: A)

    /**
     * What headless settling compares, round after round, by `equals`. It must change whenever the app does anything
     * a later round could depend on: a change of state, a unit of async work starting or finishing.
     */
    public fun fingerprint(): Any

    /**
     * Throws the app away, cancelling its work, and builds a fresh one from its initial state over the same
     * environment: `device relaunch`. Then calls [onRelaunch].
     */
    public fun relaunch()

    /** The app's state as `--diff` compares it, before and after a step. */
    public fun stateSnapshot(): Any?

    /** What changed since [before] (a [stateSnapshot]), for `--diff`; `null` or empty when nothing did. */
    public fun stateDiff(before: Any?): String?

    /** The whole state, as `state` and `GET /state` print it. */
    public val stateDump: String

    /** Called after [relaunch] with the new [app] in place: the running app's shell shows it. */
    public var onRelaunch: (() -> Unit)?
}

/** This environment with another [scope]: what each launch of an app is built on. */
internal fun AgentEnvironment.withScope(scope: CoroutineScope): AgentEnvironment =
    AgentEnvironment(scope, clock, mocks, uuids, random, zone, locale, device)

/** A scope whose job is a child of [parent]'s: cancelling it leaves [parent] running, cancelling [parent] ends it. */
internal fun childScope(parent: CoroutineScope): CoroutineScope =
    CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job))
