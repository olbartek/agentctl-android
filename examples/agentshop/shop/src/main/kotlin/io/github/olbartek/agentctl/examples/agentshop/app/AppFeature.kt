package io.github.olbartek.agentctl.examples.agentshop.app

import io.github.olbartek.agentctl.ActiveScreen
import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentContainer
import io.github.olbartek.agentctl.CommandDoc
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.EffectScope
import io.github.olbartek.agentctl.Next
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.appendingBackFallback
import io.github.olbartek.agentctl.examples.agentshop.auth.AuthFlow
import io.github.olbartek.agentctl.examples.agentshop.auth.AuthFlowAgent
import io.github.olbartek.agentctl.examples.agentshop.clients.MockAccounts
import io.github.olbartek.agentctl.examples.agentshop.home.HomeTabs
import io.github.olbartek.agentctl.examples.agentshop.home.HomeTabsAgent
import io.github.olbartek.agentctl.examples.agentshop.models.Outcome
import io.github.olbartek.agentctl.examples.agentshop.models.Session
import io.github.olbartek.agentctl.examples.agentshop.models.attempt
import io.github.olbartek.agentctl.examples.agentshop.onboarding.OnboardingFlow
import io.github.olbartek.agentctl.examples.agentshop.onboarding.OnboardingFlowAgent
import io.github.olbartek.agentctl.next

/**
 * The root: launching, then auth, onboarding (once, for a new account) or home.
 *
 * This is the one type AgentCtl is generic over: `ScriptRunner<AppFeature.State, AppFeature.Action>` drives its store,
 * and [AppFeatureAgent] resolves which screen an agent is looking at. A UI renders [State] by its case and sends
 * [Action]s: each case's screens are reached through [Action.Auth], [Action.Onboarding] and [Action.Home].
 */
object AppFeature {
    sealed interface State {
        data object Launching : State
        data class Auth(val state: AuthFlow.State) : State
        data class Onboarding(val state: OnboardingFlow.State) : State
        data class Home(val state: HomeTabs.State) : State
    }

    sealed interface Action {
        /** The launching screen appeared: restore the saved session, if any. */
        data object Appeared : Action
        data class SessionLoaded(val session: Session?) : Action
        data class Auth(val action: AuthFlow.Action) : Action
        data class Onboarding(val action: OnboardingFlow.Action) : Action
        data class Home(val action: HomeTabs.Action) : Action

        /** Signed in and the account's profile is loaded: onboarding first if it still needs it, else home. */
        data class SignedIn(val session: Session, val needsOnboarding: Boolean) : Action
        data object SignedOut : Action

        /** Saves a seeded account's session and goes straight home (`login-as`). */
        data class LoginAs(val session: Session) : Action

        /** Restarts from a fresh launch: the saved session and the mock backends are kept. */
        data object Reset : Action
    }

    /** The scope each case's effects run in, cancelled when the app leaves the case (TCA's `ifCaseLet`). */
    private enum class CaseScope { AUTH, ONBOARDING, HOME }

    private fun State.caseScope(): CaseScope? = when (this) {
        State.Launching -> null
        is State.Auth -> CaseScope.AUTH
        is State.Onboarding -> CaseScope.ONBOARDING
        is State.Home -> CaseScope.HOME
    }

    fun reducer(dependencies: ShopDependencies): Reducer<State, Action> {
        val sessionClient = dependencies.sessionClient
        val accountClient = dependencies.accountClient
        val auth = AuthFlow.reducer(dependencies.authClient, dependencies.clock)
        val onboarding = OnboardingFlow.reducer(accountClient)
        val home = HomeTabs.reducer(dependencies.catalogClient, dependencies.cartClient, dependencies.ordersClient, accountClient)

        fun homeState(session: Session): State = State.Home(HomeTabs.State(dependencies.uuids(), session))

        /**
         * Saves the session (restored on the next launch only when `remember` is set), then asks the account server
         * whether this account still needs onboarding. If that fails the shopper goes home: onboarding is never a
         * reason to lock someone out.
         */
        fun signIn(session: Session, remember: Boolean): Effect<Action> = Effect.run { send ->
            sessionClient.save(session, remember)
            val profile = attempt({ it }) { accountClient.fetchProfile() }
            send(Action.SignedIn(session, needsOnboarding = (profile as? Outcome.Success)?.value?.needsOnboarding ?: false))
        }

        /** The current case's own reducer, for an action meant for it; its effects are scoped to the case. */
        fun child(state: State, action: Action): Next<State, Action> = when {
            state is State.Auth && action is Action.Auth -> auth.reduce(state.state, action.action)
                .let { Next(State.Auth(it.state), it.effect.map<Action> { a -> Action.Auth(a) }.scoped(CaseScope.AUTH)) }
            state is State.Onboarding && action is Action.Onboarding -> onboarding.reduce(state.state, action.action)
                .let { Next(State.Onboarding(it.state), it.effect.map<Action> { a -> Action.Onboarding(a) }.scoped(CaseScope.ONBOARDING)) }
            state is State.Home && action is Action.Home -> home.reduce(state.state, action.action)
                .let { Next(State.Home(it.state), it.effect.map<Action> { a -> Action.Home(a) }.scoped(CaseScope.HOME)) }
            else -> next(state)
        }

        fun parent(state: State, action: Action): Next<State, Action> = when (action) {
            Action.Appeared ->
                if (state != State.Launching) {
                    next(state)
                } else {
                    next(state, Effect.run { send -> send(Action.SessionLoaded(sessionClient.current())) })
                }
            is Action.SessionLoaded -> next(action.session?.let(::homeState) ?: State.Auth(AuthFlow.State()))
            is Action.Auth -> when (val delegate = action.action) {
                is AuthFlow.Action.Delegate.Authenticated -> next(state, signIn(delegate.session, delegate.remember))
                else -> next(state)
            }
            is Action.LoginAs -> next(state, signIn(action.session, remember = true))
            is Action.SignedIn ->
                next(if (action.needsOnboarding) State.Onboarding(OnboardingFlow.State(action.session)) else homeState(action.session))
            is Action.Onboarding -> when (val delegate = action.action) {
                is OnboardingFlow.Action.Delegate.Finished -> next(homeState(delegate.session))
                else -> next(state)
            }
            is Action.Home ->
                if (action.action == HomeTabs.Action.Delegate.LoggedOut) {
                    next(state, Effect.run { send ->
                        sessionClient.clear()
                        send(Action.SignedOut)
                    })
                } else {
                    next(state)
                }
            Action.SignedOut -> next(State.Auth(AuthFlow.State()))
            // The launching screen appears again and sends `Appeared`, which restores the session.
            Action.Reset -> next(State.Launching)
        }

        return Reducer { state, action ->
            val childResult = child(state, action)
            val before = childResult.state
            val parentResult = parent(before, action)
            val after = parentResult.state
            // Leaving a case — or replacing home with a new one — cancels what the old one was running.
            val left = before.caseScope()
            val changed = left != after.caseScope() ||
                (before is State.Home && after is State.Home && before.state.id != after.state.id)
            val cancel: Effect<Action> = if (left != null && changed) Effect.cancel(EffectScope(left)) else Effect.None
            next(after, Effect.merge(childResult.effect, parentResult.effect, cancel))
        }
    }
}

object AppFeatureAgent : AgentContainer<AppFeature.State, AppFeature.Action> {
    private const val LOGIN_AS_HELP = "Save a seeded account's session and go straight home."
    private const val RESET_HELP = "Restart from a fresh launch (keeps the saved session and the mock data)."

    /** Root commands, available on every screen: `inheritingCommands` adds them to the active screen and the registry. */
    override val inheritedCommands: List<AgentCommand<AppFeature.State, AppFeature.Action>> = listOf(
        AgentCommand.choice(
            "login-as",
            listOf("alice" to MockAccounts.alice.user, "bob" to MockAccounts.bob.user),
            help = LOGIN_AS_HELP,
        ) { AppFeature.Action.LoginAs(MockAccounts.session(it)) },
        AgentCommand.action("reset", help = RESET_HELP, action = AppFeature.Action.Reset),
    )

    override fun activeScreen(state: AppFeature.State): ActiveScreen<AppFeature.Action> {
        val screen: ActiveScreen<AppFeature.Action> = when (state) {
            AppFeature.State.Launching -> ActiveScreen(
                path = "launching",
                identity = "launching",
                summary = emptyList(),
                errorCode = null,
                appearAction = AppFeature.Action.Appeared,
                commands = emptyList(),
            )
            is AppFeature.State.Auth -> AuthFlowAgent.activeScreen(state.state).map { AppFeature.Action.Auth(it) }.identified("auth")
            is AppFeature.State.Onboarding ->
                OnboardingFlowAgent.activeScreen(state.state).map { AppFeature.Action.Onboarding(it) }.identified("onboarding")
            is AppFeature.State.Home -> HomeTabsAgent.activeScreen(state.state).map { AppFeature.Action.Home(it) }
        }
        // `back` when no container has anything to pop: a clear error instead of "unknown command".
        return inheritingCommands(screen, state).appendingBackFallback(containerName)
    }

    override val registry: List<ScreenDoc>
        get() {
            val screens = listOf(ScreenDoc("launching", "AppFeature", emptyList(), emptyList())) +
                AuthFlowAgent.registry + OnboardingFlowAgent.registry + HomeTabsAgent.registry
            return inheritingCommands(screens).map { it.inheriting(listOf(CommandDoc.backFallback(containerName))) }
        }
}
