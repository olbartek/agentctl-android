package io.github.olbartek.agentctl.examples.agentshop.onboarding

import io.github.olbartek.agentctl.ActiveScreen
import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentContainer
import io.github.olbartek.agentctl.CommandDoc
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Next
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.appending
import io.github.olbartek.agentctl.examples.agentshop.clients.AccountClient
import io.github.olbartek.agentctl.examples.agentshop.models.Coded
import io.github.olbartek.agentctl.examples.agentshop.models.Session
import io.github.olbartek.agentctl.next

/**
 * Onboarding, once per new account: welcome → interests → address → notifications, then home.
 *
 * A linear flow rather than a navigation stack: each step's state is kept, so going back to interests shows what was
 * picked.
 */
object OnboardingFlow {
    enum class Step(override val code: String) : Coded {
        WELCOME("welcome"),
        INTERESTS("interests"),
        ADDRESS("address"),
        NOTIFICATIONS("notifications"),
    }

    data class State(
        val session: Session,
        val step: Step = Step.WELCOME,
        val welcome: Welcome.State = Welcome.State(),
        val interests: Interests.State = Interests.State(),
        val address: AddressForm.State = AddressForm.State(),
        val notifications: Notifications.State = Notifications.State(),
    )

    sealed interface Action {
        data class Welcome(val action: io.github.olbartek.agentctl.examples.agentshop.onboarding.Welcome.Action) : Action
        data class Interests(val action: io.github.olbartek.agentctl.examples.agentshop.onboarding.Interests.Action) : Action
        data class Address(val action: AddressForm.Action) : Action
        data class Notifications(val action: io.github.olbartek.agentctl.examples.agentshop.onboarding.Notifications.Action) : Action

        /** The back button: the previous step. */
        data object BackTapped : Action

        sealed interface Delegate : Action {
            data class Finished(val session: Session) : Delegate
        }
    }

    fun reducer(accountClient: AccountClient): Reducer<State, Action> {
        val notifications = Notifications.reducer(accountClient)
        return Reducer { state, action ->
            // The step's own reducer first, then the flow's.
            val child: Next<State, Action> = when (action) {
                is Action.Welcome -> Welcome.reducer.reduce(state.welcome, action.action)
                    .let { Next(state.copy(welcome = it.state), it.effect.map { a -> Action.Welcome(a) }) }
                is Action.Interests -> Interests.reducer.reduce(state.interests, action.action)
                    .let { Next(state.copy(interests = it.state), it.effect.map { a -> Action.Interests(a) }) }
                is Action.Address -> AddressForm.reducer.reduce(state.address, action.action)
                    .let { Next(state.copy(address = it.state), it.effect.map { a -> Action.Address(a) }) }
                is Action.Notifications -> notifications.reduce(state.notifications, action.action)
                    .let { Next(state.copy(notifications = it.state), it.effect.map { a -> Action.Notifications(a) }) }
                else -> next(state)
            }
            val current = child.state
            val parent: Next<State, Action> = when {
                action is Action.Welcome && action.action == Welcome.Action.Delegate.Finished -> next(current.copy(step = Step.INTERESTS))
                action is Action.Interests && action.action is Interests.Action.Delegate.Chose -> next(
                    current.copy(
                        notifications = current.notifications.copy(interests = action.action.interests),
                        step = Step.ADDRESS,
                    ),
                )
                action is Action.Address && action.action is AddressForm.Action.Delegate.Finished -> next(
                    current.copy(
                        notifications = current.notifications.copy(address = action.action.address),
                        step = Step.NOTIFICATIONS,
                    ),
                )
                action is Action.Notifications && action.action is Notifications.Action.Delegate.Finished ->
                    next(current, Effect.send(Action.Delegate.Finished(current.session)))
                action == Action.BackTapped -> next(
                    when (current.step) {
                        Step.WELCOME -> current
                        Step.INTERESTS -> current.copy(step = Step.WELCOME)
                        Step.ADDRESS -> current.copy(step = Step.INTERESTS)
                        // Not while the answers are being saved.
                        Step.NOTIFICATIONS -> if (current.notifications.isLoading) current else current.copy(step = Step.ADDRESS)
                    },
                )
                else -> next(current)
            }
            next(parent.state, Effect.merge(child.effect, parent.effect))
        }
    }
}

object OnboardingFlowAgent : AgentContainer<OnboardingFlow.State, OnboardingFlow.Action> {
    private const val BACK_HELP = "Go back to the previous step."

    override fun activeScreen(state: OnboardingFlow.State): ActiveScreen<OnboardingFlow.Action> {
        val back = AgentCommand.action<OnboardingFlow.State, OnboardingFlow.Action>(
            "back",
            help = BACK_HELP,
            action = OnboardingFlow.Action.BackTapped,
        ).resolve(state, source = "OnboardingFlow")
        return when (state.step) {
            // Welcome's own `back` pages the carousel; there is no step before it.
            OnboardingFlow.Step.WELCOME -> WelcomeAgent.activeScreen(state.welcome).map { OnboardingFlow.Action.Welcome(it) }
            OnboardingFlow.Step.INTERESTS ->
                InterestsAgent.activeScreen(state.interests).map { OnboardingFlow.Action.Interests(it) }.appending(listOf(back))
            OnboardingFlow.Step.ADDRESS ->
                AddressFormAgent.activeScreen(state.address).map { OnboardingFlow.Action.Address(it) }.appending(listOf(back))
            OnboardingFlow.Step.NOTIFICATIONS ->
                NotificationsAgent.activeScreen(state.notifications).map { OnboardingFlow.Action.Notifications(it) }.appending(listOf(back))
        }
    }

    override val registry: List<ScreenDoc>
        get() {
            val back = CommandDoc("back", null, BACK_HELP, "OnboardingFlow")
            return WelcomeAgent.screenDocs +
                (InterestsAgent.screenDocs + AddressFormAgent.screenDocs + NotificationsAgent.screenDocs).map { it.inheriting(listOf(back)) }
        }
}
