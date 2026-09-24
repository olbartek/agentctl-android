package io.github.olbartek.agentctl.examples.tinyapp

import io.github.olbartek.agentctl.ActiveScreen
import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentContainer
import io.github.olbartek.agentctl.CommandDoc
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.appending

/**
 * A container resolves the screen an agent is looking at, lifts that screen's commands to its own action type,
 * and adds the commands it owns itself — here `back`, which pops the stack.
 */
object TinyRootAgent : AgentContainer<TinyRoot.State, TinyRoot.Action> {
    private const val BACK_HELP = "Go back to the list."

    override fun activeScreen(state: TinyRoot.State): ActiveScreen<TinyRoot.Action> {
        val top = state.path.lastOrNull() ?: return ItemsAgent.activeScreen(state.items).map { TinyRoot.Action.Items(it) }
        val child: ActiveScreen<TinyRoot.Action> = when (val screen = top.screen) {
            is TinyRoot.Path.Detail -> ItemDetailAgent.activeScreen(screen.state).map { TinyRoot.Action.Detail(top.id, it) }
        }
        val back = AgentCommand.action<TinyRoot.State, TinyRoot.Action>("back", help = BACK_HELP, action = TinyRoot.Action.PopFrom(top.id))
            .resolve(state, source = "TinyRoot")
        // The stack element's id is part of the screen's identity, so pushing the same path twice counts as a new
        // appearance and the screen's `onAppear` is sent again.
        return child.identified("#${top.id}").appending(listOf(back))
    }

    /** Every screen the app can show, for `screens` and the generated docs. Pushed screens inherit `back`. */
    override val registry: List<ScreenDoc>
        get() {
            val back = CommandDoc("back", null, BACK_HELP, "TinyRoot")
            return ItemsAgent.screenDocs + ItemDetailAgent.screenDocs.map { it.inheriting(listOf(back)) }
        }
}
