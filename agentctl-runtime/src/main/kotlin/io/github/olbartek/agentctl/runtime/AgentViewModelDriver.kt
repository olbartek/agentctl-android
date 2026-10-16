package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.ActiveScreen
import io.github.olbartek.agentctl.AgentCoordinator
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.AgentIntent
import io.github.olbartek.agentctl.SummaryItem
import kotlinx.coroutines.cancel

/**
 * The [AgentDriver] of an MVVM app: an [AgentCoordinator] and its view models (Jetpack `ViewModel`s, or any class).
 *
 * - The coordinator is built on an environment of its own launch: the host's, with a scope a relaunch cancels. View
 *   models start their work through that environment's [io.github.olbartek.agentctl.AgentTasks]; a relaunch calls
 *   [AgentCoordinator.cancelAllWork] and cancels the scope too, so no work outlives the app it belonged to.
 * - There is no root state to compare. The fingerprint is the active screen as an agent sees it (identity, path,
 *   summary, error) plus the tasks in flight: a change no summary shows cannot be what a later round waits on, since
 *   any waiting is a task, and tasks are counted.
 *
 * @param hostEnvironment the host's environment, whose `scope` outlives every launch.
 * @param coordinator builds the coordinator on a launch's environment; called again on every relaunch. Whatever it
 *   builds starts over; what outlives a relaunch (a client's storage) is built outside it.
 */
public class AgentViewModelDriver<C : AgentCoordinator<C>>(
    private val hostEnvironment: AgentEnvironment,
    private val coordinator: (AgentEnvironment) -> C,
) : AgentDriver<AgentIntent<C>, C> {
    /** What the current coordinator was built on. A relaunch replaces it, with a fresh scope. */
    public var environment: AgentEnvironment = hostEnvironment.withScope(childScope(hostEnvironment.scope))
        private set

    /** The coordinator the app shows. A relaunch replaces it. */
    override var app: C = coordinator(environment)
        private set

    override var onRelaunch: (() -> Unit)? = null

    override fun activeScreen(): ActiveScreen<AgentIntent<C>> = app.activeScreen()

    override fun send(action: AgentIntent<C>): Unit = action.run(app)

    private data class Fingerprint(
        val identity: String,
        val path: String,
        val summary: List<SummaryItem>,
        val error: String?,
        val tasks: Int,
    )

    override fun fingerprint(): Any {
        val screen = app.activeScreen()
        return Fingerprint(screen.identity, screen.path, screen.summary, screen.errorCode, environment.tasks.inFlight)
    }

    override fun relaunch() {
        app.cancelAllWork()
        environment.scope.cancel()
        environment = hostEnvironment.withScope(childScope(hostEnvironment.scope))
        app = coordinator(environment)
        onRelaunch?.invoke()
    }

    override fun stateSnapshot(): Any? = stateDump

    override fun stateDiff(before: Any?): String = StateDump.diffText(before as? String ?: "", stateDump)

    override val stateDump: String get() = app.agentStateDump
}
