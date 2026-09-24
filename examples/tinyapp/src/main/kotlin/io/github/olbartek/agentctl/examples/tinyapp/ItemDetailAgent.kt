package io.github.olbartek.agentctl.examples.tinyapp

import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.SummaryItem

/**
 * The detail screen's agent surface. Its path carries the item's id, which is how `expect screen=items/2` tells
 * one pushed screen from another.
 */
object ItemDetailAgent : AgentScreen<ItemDetail.State, ItemDetail.Action> {
    /** `<id>` stands for the variable part: this is the path as the docs list it, once. */
    override val screenPaths = listOf("items/<id>")

    override fun screenPath(state: ItemDetail.State) = "items/${state.item.id}"

    override val summaryKeys = listOf("title", "saved", "cooldown")

    override fun summary(state: ItemDetail.State) = listOf(
        SummaryItem("title", state.item.title),
        SummaryItem("saved", state.saved),
        SummaryItem("cooldown", state.cooldown),
    )

    override fun errorCode(state: ItemDetail.State) = state.error?.code

    override val commands: List<AgentCommand<ItemDetail.State, ItemDetail.Action>> = listOf(
        AgentCommand.action(
            "save",
            help = "Save this item. A save starts a ${ItemDetail.COOLDOWN_SECONDS}-second cooldown; saving again " +
                "before it runs out reports error=cooldown.",
            action = ItemDetail.Action.SaveTapped,
        ),
    )
}
