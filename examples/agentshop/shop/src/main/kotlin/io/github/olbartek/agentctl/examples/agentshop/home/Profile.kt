package io.github.olbartek.agentctl.examples.agentshop.home

import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentDevice
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.CommandGate
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.examples.agentshop.clients.BiometricsClient
import io.github.olbartek.agentctl.examples.agentshop.clients.FaceIDVault
import io.github.olbartek.agentctl.examples.agentshop.clients.NotificationsClient
import io.github.olbartek.agentctl.examples.agentshop.models.Session
import io.github.olbartek.agentctl.examples.agentshop.models.User
import io.github.olbartek.agentctl.next

/**
 * The signed-in user. Logging out asks for confirmation first.
 *
 * Order updates need permission to send notifications: turning them on asks for it, and a denial turns them off again
 * with `notificationsDenied`. Face ID sign-in is turned on by passing Face ID once; the account is then kept for the
 * login screen's `face-id`.
 */
object Profile {
    enum class Error(val code: String) {
        NOTIFICATIONS_DENIED("notificationsDenied"),
        BIOMETRY_FAILED("biometryFailed"),
        BIOMETRY_UNAVAILABLE("biometryUnavailable"),
    }

    /** The alert the screen shows, with the text a UI renders for it. */
    enum class Alert(val title: String, val message: String, val confirm: String, val cancel: String) {
        CONFIRM_LOGOUT("Log out?", "You will need to sign in again.", "Log out", "Cancel"),
    }

    data class State(
        val user: User,
        /** The signed-in session, kept for Face ID sign-in. */
        val session: Session? = null,
        val orderUpdates: Boolean = false,
        val faceID: Boolean = false,
        val error: Error? = null,
        val alert: Alert? = null,
    )

    sealed interface Action {
        data object OnAppear : Action
        data class OrderUpdatesToggled(val on: Boolean) : Action
        data class NotificationsAnswered(val status: AgentDevice.PermissionStatus) : Action
        data class FaceIDToggled(val on: Boolean) : Action
        data class FaceIDAnswered(val result: AgentDevice.BiometryResult) : Action
        data object LogoutTapped : Action

        /** The alert's destructive button. Like any alert button, it also dismisses the alert. */
        data object ConfirmLogoutTapped : Action

        /** The alert's cancel button, or the alert dismissed any other way. */
        data object AlertDismissed : Action

        sealed interface Delegate : Action {
            data object LoggedOut : Delegate
        }
    }

    fun reducer(
        notificationsClient: NotificationsClient = NotificationsClient(),
        biometricsClient: BiometricsClient = BiometricsClient(),
        faceIDVault: FaceIDVault = FaceIDVault(),
    ): Reducer<State, Action> = Reducer { state, action ->
        when (action) {
            Action.OnAppear -> next(state.copy(faceID = faceIDVault.session?.user == state.user))

            is Action.OrderUpdatesToggled ->
                if (!action.on) {
                    next(state.copy(error = null, orderUpdates = false))
                } else {
                    next(
                        state.copy(error = null),
                        Effect.run { send -> send(Action.NotificationsAnswered(notificationsClient.requestAuthorization())) },
                    )
                }

            is Action.NotificationsAnswered -> {
                val on = action.status == AgentDevice.PermissionStatus.GRANTED || action.status == AgentDevice.PermissionStatus.LIMITED
                next(state.copy(orderUpdates = on, error = if (on) null else Error.NOTIFICATIONS_DENIED))
            }

            is Action.FaceIDToggled ->
                if (!action.on) {
                    faceIDVault.disable()
                    next(state.copy(error = null, faceID = false))
                } else {
                    next(state.copy(error = null), Effect.run { send -> send(Action.FaceIDAnswered(biometricsClient.authenticate())) })
                }

            is Action.FaceIDAnswered -> when (action.result) {
                AgentDevice.BiometryResult.SUCCESS -> {
                    state.session?.let(faceIDVault::enable)
                    next(state.copy(faceID = true))
                }
                AgentDevice.BiometryResult.FAILURE -> next(state.copy(error = Error.BIOMETRY_FAILED))
                AgentDevice.BiometryResult.UNAVAILABLE -> next(state.copy(error = Error.BIOMETRY_UNAVAILABLE))
            }

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

    override val summaryKeys: List<String> = listOf("name", "email", "orderUpdates", "faceID", "alert")

    override fun summary(state: Profile.State): List<SummaryItem> = listOf(
        SummaryItem("name", state.user.name),
        SummaryItem("email", state.user.email),
        SummaryItem("orderUpdates", if (state.orderUpdates) "on" else "off"),
        SummaryItem("faceID", if (state.faceID) "on" else "off"),
        SummaryItem("alert", if (state.alert == null) "none" else "logout"),
    )

    override fun errorCode(state: Profile.State): String? = state.error?.code

    /** Headlessly there is no view to send this, so the runtime sends it when the screen becomes active. */
    override val onAppear: Profile.Action = Profile.Action.OnAppear

    override val commands: List<AgentCommand<Profile.State, Profile.Action>> = listOf(
        AgentCommand.onOff(
            "order-updates",
            help = "Turn order updates on (asks for permission to send notifications) or off.",
        ) { Profile.Action.OrderUpdatesToggled(it) },
        AgentCommand.onOff(
            "face-id",
            help = "Turn Face ID sign-in on (passing Face ID once) or off; the login screen then offers face-id.",
        ) { Profile.Action.FaceIDToggled(it) },
        AgentCommand.action("logout", help = "Ask to log out (shows a confirmation alert).", action = Profile.Action.LogoutTapped),
        AgentCommand.action("confirm", help = "Confirm logging out in the alert.", action = Profile.Action.ConfirmLogoutTapped, gate = alertShown),
        AgentCommand.action("dismiss", help = "Dismiss the alert.", action = Profile.Action.AlertDismissed, gate = alertShown),
    )
}
