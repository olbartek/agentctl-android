package io.github.olbartek.agentctl.examples.tinyapp

import io.github.olbartek.agentctl.AgentClock
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.EffectScope
import io.github.olbartek.agentctl.Next
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.combine
import io.github.olbartek.agentctl.next
import io.github.olbartek.agentctl.pullback

/**
 * The app: the list at the root of a navigation stack, with a detail screen pushed on top.
 *
 * The root is the one type AgentCtl is generic over: `ScriptRunner<TinyRoot.State, TinyRoot.Action>` drives its
 * store, and [TinyRootAgent] resolves which screen an agent is looking at.
 */
object TinyRoot {
    /** A screen pushed on the stack. TinyApp has one kind; a real app lists every pushable screen here. */
    sealed interface Path {
        data class Detail(val state: ItemDetail.State) : Path
    }

    /** One element of the stack. The id is what tells two pushes of the same screen apart. */
    data class Element(val id: Int, val screen: Path)

    data class State(
        val items: Items.State = Items.State(),
        val path: List<Element> = emptyList(),
        /** The id of the next pushed element: deterministic, so a step never depends on an earlier run. */
        val nextId: Int = 0,
    )

    sealed interface Action {
        data class Items(val action: io.github.olbartek.agentctl.examples.tinyapp.Items.Action) : Action

        /** An action for the element [id] of the stack, if it is still there. */
        data class Detail(val id: Int, val action: ItemDetail.Action) : Action

        /** Pops the element [id] and everything above it, cancelling their effects. */
        data class PopFrom(val id: Int) : Action
    }

    fun reducer(client: ItemsClient, clock: AgentClock): Reducer<State, Action> {
        val items = Items.reducer(client).pullback<State, Action, Items.State, Items.Action>(
            get = { it.items },
            set = { state, child -> state.copy(items = child) },
            extract = { (it as? Action.Items)?.action },
            embed = { Action.Items(it) },
        )
        val detail = ItemDetail.reducer(clock)
        val navigation = Reducer<State, Action> { state, action ->
            when (action) {
                is Action.Items -> when (val child = action.action) {
                    is Items.Action.Open -> next(
                        state.copy(
                            path = state.path + Element(state.nextId, Path.Detail(ItemDetail.State(child.item))),
                            nextId = state.nextId + 1,
                        ),
                    )
                    else -> next(state)
                }

                is Action.Detail -> {
                    val index = state.path.indexOfFirst { it.id == action.id }
                    // An action for an element that has been popped is dropped, as a stack does.
                    if (index < 0) return@Reducer next(state)
                    val element = state.path[index]
                    val screen = element.screen as Path.Detail
                    val result: Next<ItemDetail.State, ItemDetail.Action> = detail.reduce(screen.state, action.action)
                    val path = state.path.toMutableList().also { it[index] = element.copy(screen = Path.Detail(result.state)) }
                    // Scoped by the element's id: two pushed details never cancel each other's cooldown.
                    next(state.copy(path = path), result.effect.map { Action.Detail(action.id, it) }.scoped(action.id))
                }

                is Action.PopFrom -> {
                    val index = state.path.indexOfFirst { it.id == action.id }
                    if (index < 0) return@Reducer next(state)
                    val popped = state.path.subList(index, state.path.size)
                    next(
                        state.copy(path = state.path.subList(0, index).toList()),
                        Effect.Merge(popped.map { Effect.cancel(EffectScope(it.id)) }),
                    )
                }
            }
        }
        return combine(items, navigation)
    }
}
