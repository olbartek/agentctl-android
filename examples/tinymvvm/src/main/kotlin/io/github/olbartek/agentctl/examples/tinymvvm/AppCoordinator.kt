package io.github.olbartek.agentctl.examples.tinymvvm

import io.github.olbartek.agentctl.ActiveScreen
import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentCoordinator
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.AppIntent
import io.github.olbartek.agentctl.CommandDoc
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.activeScreen
import io.github.olbartek.agentctl.appending
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The app: the list at the root of a back stack, with detail screens pushed on top. It owns navigation — the back
 * stack, which Navigation 3's `NavDisplay(backStack)` (or a `NavHost`) renders — and creates each pushed view model,
 * on its own environment, so the child's clock and clients are the app's.
 *
 * In an Android app it lives as long as the app's UI does (an activity-scoped `ViewModel`, or the `Application`).
 */
class AppCoordinator(private val environment: AgentEnvironment) : AgentCoordinator<AppCoordinator> {
    /** One pushed screen. `id` tells two pushes of the same path apart: pushing again is a new appearance. */
    data class Element(val id: Int, val detail: ItemDetailViewModel)

    val items: ItemsViewModel = ItemsViewModel(environment, ItemsClient.mock(environment.mocks))

    private val mutableBackStack = MutableStateFlow<List<Element>>(emptyList())

    /** The pushed screens, oldest first; the list is under them. */
    val backStack: StateFlow<List<Element>> = mutableBackStack.asStateFlow()

    /** Deterministic ids: a counter, not a random id. */
    private var nextId = 0

    init {
        items.onOpen = ::push
    }

    private fun push(item: Item) {
        mutableBackStack.update { it + Element(nextId, ItemDetailViewModel(environment, item)) }
        nextId += 1
    }

    fun pop() {
        val top = backStack.value.lastOrNull() ?: return
        mutableBackStack.update { it.dropLast(1) }
        top.detail.cancelAllWork()
    }

    /** A deep link or a tapped push: open that item on top of whatever is shown, as tapping it in the list would. */
    fun openItem(id: Int): Unit = items.openTapped(id)

    override fun activeScreen(): ActiveScreen<AppIntent<AppCoordinator>> {
        val top = backStack.value.lastOrNull() ?: return items.activeScreen()
        val back = AgentCommand.action<Unit, AppIntent<AppCoordinator>>("back", help = BACK_HELP, action = AppIntent { it.pop() })
            .resolve(Unit, source = "AppCoordinator")
        return top.detail.activeScreen<ItemDetailViewModel.UiState, ItemDetailViewModel, AppCoordinator>()
            .identified("#${top.id}")
            .appending(listOf(back))
    }

    override fun cancelAllWork() {
        items.cancelAllWork()
        backStack.value.forEach { it.detail.cancelAllWork() }
    }

    override val agentStateDump: String
        get() {
            val list = items.state.value
            val lines = mutableListOf(
                "AppCoordinator(",
                "  items: ItemsViewModel(items: ${list.items.size}, isLoading: ${list.isLoading}, error: ${list.error?.code ?: "null"}),",
                "  backStack: [",
            )
            for (element in backStack.value) {
                val detail = element.detail.state.value
                lines.add(
                    "    #${element.id}: detail(item: ${detail.item.id}, saved: ${detail.saved}, cooldown: ${detail.cooldown}, " +
                        "error: ${detail.error?.code ?: "null"}),",
                )
            }
            lines.addAll(listOf("  ]", ")"))
            return lines.joinToString("\n")
        }

    companion object {
        private const val BACK_HELP = "Go back to the list."

        /** Every screen the app can show, for `screens` and the generated docs. Pushed screens inherit `back`. */
        val registry: List<ScreenDoc>
            get() {
                val back = CommandDoc("back", null, BACK_HELP, "AppCoordinator")
                return ItemsViewModel.screenDocs + ItemDetailViewModel.screenDocs.map { it.inheriting(listOf(back)) }
            }
    }
}
