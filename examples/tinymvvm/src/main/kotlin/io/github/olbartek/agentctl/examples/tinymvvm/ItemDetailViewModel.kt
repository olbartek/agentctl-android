package io.github.olbartek.agentctl.examples.tinymvvm

import androidx.lifecycle.ViewModel
import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.AgentScreenIntent
import io.github.olbartek.agentctl.AgentViewModel
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.every
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The detail screen: it saves the item, then refuses to save again until a three-second cooldown has run out. The
 * countdown is a job sleeping on `environment.clock`, so a step reports it as `pending=1` and `advance` releases it.
 */
class ItemDetailViewModel(
    private val environment: AgentEnvironment,
    val item: Item,
) : ViewModel(), AgentViewModel<ItemDetailViewModel.UiState, ItemDetailViewModel> {
    /** Why a save was refused. */
    enum class SaveError(val code: String) { COOLDOWN("cooldown") }

    data class UiState(
        val item: Item,
        val saved: Boolean = false,
        /** Seconds left before the item can be saved again; `0` when it can. */
        val cooldown: Int = 0,
        val error: SaveError? = null,
    )

    private val mutableState = MutableStateFlow(UiState(item))

    /** What the screen shows. */
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private var cooldownJob: Job? = null

    fun saveTapped() {
        if (state.value.cooldown != 0) {
            mutableState.update { it.copy(error = SaveError.COOLDOWN) }
            return
        }
        mutableState.update { it.copy(error = null, saved = true, cooldown = COOLDOWN_SECONDS) }
        cooldownJob?.cancel()
        cooldownJob = environment.tasks.run {
            environment.clock.every(1.seconds) { cooldownTicked() }
        }
    }

    private fun cooldownTicked() {
        val cooldown = maxOf(0, state.value.cooldown - 1)
        if (cooldown != 0) {
            mutableState.update { it.copy(cooldown = cooldown) }
            return
        }
        // Saving is possible again, so the refusal no longer applies, and the countdown ends.
        mutableState.update { it.copy(cooldown = 0, error = null) }
        cooldownJob?.cancel()
    }

    /** A store cancels a popped screen's effects for you; here the coordinator calls this when it pops. */
    fun cancelAllWork() {
        cooldownJob?.cancel()
    }

    override fun onCleared(): Unit = cancelAllWork()

    override val agentState: UiState get() = state.value

    override val agentScreen: AgentScreen<UiState, AgentScreenIntent<ItemDetailViewModel>> get() = Companion

    /** The detail screen's agent surface: TinyApp's, over the view model's state. */
    companion object : AgentScreen<UiState, AgentScreenIntent<ItemDetailViewModel>> {
        /** The countdown a `save` starts, in seconds. */
        const val COOLDOWN_SECONDS = 3

        override val screenName: String = "ItemDetail"

        override val screenPaths = listOf("items/<id>")

        override fun screenPath(state: UiState) = "items/${state.item.id}"

        override val summaryKeys = listOf("title", "saved", "cooldown")

        override fun summary(state: UiState) = listOf(
            SummaryItem("title", state.item.title),
            SummaryItem("saved", state.saved),
            SummaryItem("cooldown", state.cooldown),
        )

        override fun errorCode(state: UiState) = state.error?.code

        override val commands: List<AgentCommand<UiState, AgentScreenIntent<ItemDetailViewModel>>> = listOf(
            AgentCommand.action(
                "save",
                help = "Save this item. A save starts a $COOLDOWN_SECONDS-second cooldown; saving again " +
                    "before it runs out reports error=cooldown.",
                action = AgentScreenIntent { it.saveTapped() },
            ),
        )
    }
}
