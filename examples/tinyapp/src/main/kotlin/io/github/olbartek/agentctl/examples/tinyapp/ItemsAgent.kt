package io.github.olbartek.agentctl.examples.tinyapp

import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.CommandGate
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.invalidArgument

/**
 * What an agent can see and do on the list. This is the whole of the screen's agent surface: a path, a compact
 * summary, an error code, the action that stands in for the view's appearance, and the commands.
 */
object ItemsAgent : AgentScreen<Items.State, Items.Action> {
    override val screenPaths = listOf("items")

    override fun screenPath(state: Items.State) = "items"

    override val summaryKeys = listOf("items", "loading")

    override fun summary(state: Items.State) = listOf(
        SummaryItem("items", state.items.size),
        SummaryItem("loading", state.isLoading),
    )

    override fun errorCode(state: Items.State) = state.error?.code

    /** Headlessly there is no view to send this, so the runtime sends it when the screen becomes active. */
    override val onAppear: Items.Action = Items.Action.OnAppear

    /**
     * A `gate` mirrors a button that is disabled: instead of sending an action that could only do nothing, the
     * runner refuses the command and tells the agent which condition closed it — `open is disabled here
     * (items=0)`. The hint reads as the reason, and the generated docs show it as "disabled when items=0".
     */
    override val commands: List<AgentCommand<Items.State, Items.Action>> = listOf(
        AgentCommand.parsing(
            "open",
            argument = "<id>",
            help = "Open an item, e.g. open 2.",
            gate = CommandGate("items=0") { it.items.isNotEmpty() },
        ) { text ->
            val id = text.toIntOrNull() ?: invalidArgument("expected an item id such as 2")
            Items.Action.OpenTapped(id)
        },
        AgentCommand.action("refresh", help = "Load the list again.", action = Items.Action.Refresh),
        AgentCommand.action(
            "retry",
            help = "Load the list again after a failure.",
            action = Items.Action.Retry,
            gate = CommandGate("error=none") { it.error != null },
        ),
    )
}
