package io.github.olbartek.agentctl.examples.tinyapp

import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.next
import kotlin.coroutines.cancellation.CancellationException

/**
 * The list screen: it loads when it appears, can be refreshed, and opens an item.
 *
 * A failed load keeps the rows that were already there and reports the error, the way a real list does.
 */
object Items {
    data class State(
        val items: List<Item> = emptyList(),
        val isLoading: Boolean = false,
        val error: ItemsError? = null,
    )

    sealed interface Action {
        data object OnAppear : Action
        data object Refresh : Action

        /** The error state's "Try again": the same load, offered only while there is a failure to clear. */
        data object Retry : Action
        data class Response(val result: Result<List<Item>>) : Action
        data class OpenTapped(val id: Int) : Action

        /** What the screen tells its container. A screen never navigates by itself. */
        data class Open(val item: Item) : Action
    }

    private object LoadId

    fun reducer(client: ItemsClient): Reducer<State, Action> = Reducer { state, action ->
        when (action) {
            Action.OnAppear, Action.Refresh, Action.Retry -> next(
                state.copy(isLoading = true, error = null),
                Effect.run<Action> { send ->
                    val result = try {
                        Result.success(client.fetch())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                    send(Action.Response(result))
                }.cancellable(LoadId, cancelInFlight = true),
            )

            is Action.Response -> action.result.fold(
                onSuccess = { items -> next(state.copy(isLoading = false, items = items)) },
                onFailure = { error -> next(state.copy(isLoading = false, error = ItemsError.of(error))) },
            )

            is Action.OpenTapped -> {
                // Never silently do nothing: an agent that asks for an item the list does not have is told so, the
                // same way a person would see it. A no-op would report a successful step and no error at all.
                val item = state.items.firstOrNull { it.id == action.id }
                    ?: return@Reducer next(state.copy(error = ItemsError.NOT_FOUND))
                next(state.copy(error = null), Effect.send(Action.Open(item)))
            }

            is Action.Open -> next(state)
        }
    }
}
