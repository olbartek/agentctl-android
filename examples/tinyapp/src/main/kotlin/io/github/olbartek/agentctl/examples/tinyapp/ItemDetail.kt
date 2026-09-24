package io.github.olbartek.agentctl.examples.tinyapp

import io.github.olbartek.agentctl.AgentClock
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.every
import io.github.olbartek.agentctl.next
import kotlin.time.Duration.Companion.seconds

/**
 * The detail screen: it saves the item, then refuses to save again until a three-second cooldown has run out.
 *
 * The cooldown is what makes this screen interesting to an agent: it is an effect suspended on the app's
 * [AgentClock], so a step reports it as `pending=1`, and headlessly `advance 3s` releases it instead of waiting
 * three real seconds.
 */
object ItemDetail {
    /** Why a save was refused. The code is what an agent sees as `error=cooldown`. */
    enum class SaveError(val code: String) { COOLDOWN("cooldown") }

    data class State(
        val item: Item,
        val saved: Boolean = false,
        /** Seconds left before the item can be saved again; `0` when it can. */
        val cooldown: Int = 0,
        val error: SaveError? = null,
    )

    sealed interface Action {
        data object SaveTapped : Action
        data object CooldownTicked : Action
    }

    /** The countdown a `save` starts, in seconds. */
    const val COOLDOWN_SECONDS = 3

    private object CooldownId

    fun reducer(clock: AgentClock): Reducer<State, Action> = Reducer { state, action ->
        when (action) {
            Action.SaveTapped -> {
                // The guard lives in the reducer, so a save that arrives during the cooldown is answered with
                // `error=cooldown` rather than silently ignored. A screen whose button is merely greyed out would
                // instead pass a `gate` to the command in `ItemDetailAgent`, and the runner would refuse the command
                // before it ever reached here.
                if (state.cooldown != 0) return@Reducer next(state.copy(error = SaveError.COOLDOWN))
                next(
                    state.copy(error = null, saved = true, cooldown = COOLDOWN_SECONDS),
                    Effect.run<Action> { send ->
                        clock.every(1.seconds) { send(Action.CooldownTicked) }
                    }.cancellable(CooldownId, cancelInFlight = true),
                )
            }

            Action.CooldownTicked -> {
                val cooldown = maxOf(0, state.cooldown - 1)
                if (cooldown != 0) return@Reducer next(state.copy(cooldown = cooldown))
                // Saving is possible again, so the refusal no longer applies.
                next(state.copy(cooldown = 0, error = null), Effect.cancel(CooldownId))
            }
        }
    }
}
