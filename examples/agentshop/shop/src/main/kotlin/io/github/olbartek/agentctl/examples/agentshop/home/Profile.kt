package io.github.olbartek.agentctl.examples.agentshop.home

import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.CommandGate
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.examples.agentshop.models.User
import io.github.olbartek.agentctl.next

/** The signed-in user. Logging out asks for confirmation first. */
object Profile {
    /** The alert the screen shows, with the text a UI renders for it. */
    enum class Alert(val title: String, val message: String, val confirm: String, val cancel: String) {
        CONFIRM_LOGOUT("Log out?", "You will need to sign in again.", "Log out", "Cancel"),
    }

    data class State(val user: User, val alert: Alert? = null)

    sealed interface Action {
        data object LogoutTapped : Action

        /** The alert's destructive button. Like any alert button, it also dismisses the alert. */
        data object ConfirmLogoutTapped : Action

        /** The alert's cancel button, or the alert dismissed any other way. */
        data object AlertDismissed : Action

        sealed interface Delegate : Action {
            data object LoggedOut : Delegate
        }
    }

    val reducer: Reducer<State, Action> = Reducer { state, action ->
        when (action) {
            Action.LogoutTapped -> next(state.copy(alert = Alert.CONFIRM_LOGOUT))
            Action.ConfirmLogoutTapped ->
                if (state.alert == null) next(state) else next(state.copy(alert = null), Effect.send(Action.Delegate.LoggedOut))
            Action.AlertDismissed -> next(state.copy(alert = null))
            is Action.Delegate -> next(state)
        }
    }
}

object ProfileAgent : AgentScreen<Profile.State, Profile.Action> {
    private val alertShown = CommandGate<Profile.State>("alert=none") { it.alert != null }

    override val screenPaths: List<String> = listOf("home/profile")

    override fun screenPath(state: Profile.State): String = "home/profile"

    override val summaryKeys: List<String> = listOf("name", "email", "alert")

    override fun summary(state: Profile.State): List<SummaryItem> = listOf(
        SummaryItem("name", state.user.name),
        SummaryItem("email", state.user.email),
        SummaryItem("alert", if (state.alert == null) "none" else "logout"),
    )

    override val commands: List<AgentCommand<Profile.State, Profile.Action>> = listOf(
        AgentCommand.action("logout", help = "Ask to log out (shows a confirmation alert).", action = Profile.Action.LogoutTapped),
        AgentCommand.action("confirm", help = "Confirm logging out in the alert.", action = Profile.Action.ConfirmLogoutTapped, gate = alertShown),
        AgentCommand.action("dismiss", help = "Dismiss the alert.", action = Profile.Action.AlertDismissed, gate = alertShown),
    )
}
