package io.github.olbartek.agentctl.examples.tinymvvm

import androidx.lifecycle.ViewModel
import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.AgentNumbers
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.AgentScreenIntent
import io.github.olbartek.agentctl.AgentViewModel
import io.github.olbartek.agentctl.CommandGate
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.invalidArgument
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive

/**
 * The list, as a Jetpack [ViewModel]: it loads when it appears, can be refreshed, and asks the coordinator to open an
 * item. Its state is a [StateFlow] a Compose screen collects; its work starts through `environment.tasks`, never
 * `viewModelScope`, so settling sees it and it runs on the host's dispatcher (virtual time headlessly).
 */
class ItemsViewModel(
    private val environment: AgentEnvironment,
    private val client: ItemsClient,
) : ViewModel(), AgentViewModel<ItemsViewModel.UiState, ItemsViewModel> {
    data class UiState(
        val items: List<Item> = emptyList(),
        val isLoading: Boolean = false,
        val error: ItemsError? = null,
    )

    private val mutableState = MutableStateFlow(UiState())

    /** What the screen shows. */
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    /** The coordinator's: a view model never navigates by itself. */
    var onOpen: (Item) -> Unit = {}

    private var loadJob: Job? = null

    fun onAppear(): Unit = load()

    fun refresh(): Unit = load()

    fun retry(): Unit = load()

    /**
     * A store's `cancellable(id, cancelInFlight = true)` by hand: cancel the previous load, and never apply a cancelled
     * load's result.
     */
    private fun load() {
        mutableState.update { it.copy(isLoading = true, error = null) }
        loadJob?.cancel()
        loadJob = environment.tasks.run {
            try {
                val items = client.fetch()
                if (isActive) mutableState.update { it.copy(isLoading = false, items = items) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isActive) mutableState.update { it.copy(isLoading = false, error = ItemsError.of(error)) }
            }
        }
    }

    fun openTapped(id: Int) {
        // Never silently do nothing: an item the list does not have is reported, as TinyApp does.
        val item = state.value.items.firstOrNull { it.id == id }
        if (item == null) {
            mutableState.update { it.copy(error = ItemsError.NOT_FOUND) }
            return
        }
        mutableState.update { it.copy(error = null) }
        onOpen(item)
    }

    fun cancelAllWork() {
        loadJob?.cancel()
    }

    override fun onCleared(): Unit = cancelAllWork()

    override val agentState: UiState get() = state.value

    override val agentScreen: AgentScreen<UiState, AgentScreenIntent<ItemsViewModel>> get() = Companion

    /** The list's agent surface: TinyApp's, over the view model's state. */
    companion object : AgentScreen<UiState, AgentScreenIntent<ItemsViewModel>> {
        override val screenName: String = "Items"

        override val screenPaths = listOf("items")

        override fun screenPath(state: UiState) = "items"

        override val summaryKeys = listOf("items", "loading")

        override fun summary(state: UiState) = listOf(SummaryItem("items", state.items.size), SummaryItem("loading", state.isLoading))

        override fun errorCode(state: UiState) = state.error?.code

        /** Headlessly there is no view to call it, so the runtime sends it when the screen becomes active. */
        override val onAppear: AgentScreenIntent<ItemsViewModel> = AgentScreenIntent { it.onAppear() }

        override val commands: List<AgentCommand<UiState, AgentScreenIntent<ItemsViewModel>>> = listOf(
            AgentCommand.parsing(
                "open",
                argument = "<id>",
                help = "Open an item, e.g. open 2.",
                gate = CommandGate("items=0") { it.items.isNotEmpty() },
            ) { text ->
                val id = AgentNumbers.int(text) ?: invalidArgument("expected an item id such as 2")
                AgentScreenIntent { it.openTapped(id) }
            },
            AgentCommand.action("refresh", help = "Load the list again.", action = AgentScreenIntent { it.refresh() }),
            AgentCommand.action(
                "retry",
                help = "Load the list again after a failure.",
                action = AgentScreenIntent { it.retry() },
                gate = CommandGate("error=none") { it.error != null },
            ),
        )
    }
}
