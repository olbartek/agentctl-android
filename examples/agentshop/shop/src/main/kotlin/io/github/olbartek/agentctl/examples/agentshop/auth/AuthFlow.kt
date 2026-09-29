package io.github.olbartek.agentctl.examples.agentshop.auth

import io.github.olbartek.agentctl.ActiveScreen
import io.github.olbartek.agentctl.AgentClock
import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentContainer
import io.github.olbartek.agentctl.CommandDoc
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Next
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.appending
import io.github.olbartek.agentctl.examples.agentshop.clients.AuthClient
import io.github.olbartek.agentctl.examples.agentshop.models.Session
import io.github.olbartek.agentctl.examples.agentshop.navigation.Stack
import io.github.olbartek.agentctl.next

/**
 * The auth navigation stack: Login at the root, with OTP login, registration (then email verification) and forgot
 * password pushed on top.
 *
 * Every sign-in is reported with Login's "Keep me signed in" choice, whichever screen it came from.
 */
object AuthFlow {
    /** A screen pushed above Login. */
    sealed interface Path {
        data class OTPLogin(val state: io.github.olbartek.agentctl.examples.agentshop.auth.OTPLogin.State) : Path
        data class Register(val state: io.github.olbartek.agentctl.examples.agentshop.auth.Register.State) : Path
        data class VerifyEmail(val state: io.github.olbartek.agentctl.examples.agentshop.auth.VerifyEmail.State) : Path
        data class ForgotPassword(val state: io.github.olbartek.agentctl.examples.agentshop.auth.ForgotPassword.State) : Path
    }

    /** An action for a pushed screen, matching its [Path]. */
    sealed interface PathAction {
        data class OTPLogin(val action: io.github.olbartek.agentctl.examples.agentshop.auth.OTPLogin.Action) : PathAction
        data class Register(val action: io.github.olbartek.agentctl.examples.agentshop.auth.Register.Action) : PathAction
        data class VerifyEmail(val action: io.github.olbartek.agentctl.examples.agentshop.auth.VerifyEmail.Action) : PathAction
        data class ForgotPassword(val action: io.github.olbartek.agentctl.examples.agentshop.auth.ForgotPassword.Action) : PathAction
    }

    data class State(val login: Login.State = Login.State(), val path: Stack<Path> = Stack("auth"))

    sealed interface Action {
        data class Login(val action: io.github.olbartek.agentctl.examples.agentshop.auth.Login.Action) : Action

        /** An action for the stack element [id], if it is still there. */
        data class Element(val id: Int, val action: PathAction) : Action

        /** Pops the element [id] and everything above it, cancelling their effects. */
        data class PopFrom(val id: Int) : Action

        /** What the flow tells the app. */
        sealed interface Delegate : Action {
            /** Signed in. `remember` is "Keep me signed in": whether to restore the session on the next launch. */
            data class Authenticated(val session: Session, val remember: Boolean) : Delegate
        }
    }

    fun reducer(authClient: AuthClient, clock: AgentClock): Reducer<State, Action> {
        val login = io.github.olbartek.agentctl.examples.agentshop.auth.Login.reducer(authClient)
        val otpLogin = OTPLogin.reducer(authClient, clock)
        val register = Register.reducer(authClient)
        val verifyEmail = VerifyEmail.reducer(authClient, clock)
        val forgotPassword = ForgotPassword.reducer(authClient)

        /** The pushed screen's own reducer, for an action that matches the screen; `null` for a mismatch. */
        fun reduceScreen(screen: Path, action: PathAction): Next<Path, PathAction>? = when {
            screen is Path.OTPLogin && action is PathAction.OTPLogin ->
                otpLogin.reduce(screen.state, action.action).lift(Path::OTPLogin, PathAction::OTPLogin)
            screen is Path.Register && action is PathAction.Register ->
                register.reduce(screen.state, action.action).lift(Path::Register, PathAction::Register)
            screen is Path.VerifyEmail && action is PathAction.VerifyEmail ->
                verifyEmail.reduce(screen.state, action.action).lift(Path::VerifyEmail, PathAction::VerifyEmail)
            screen is Path.ForgotPassword && action is PathAction.ForgotPassword ->
                forgotPassword.reduce(screen.state, action.action).lift(Path::ForgotPassword, PathAction::ForgotPassword)
            else -> null
        }

        fun authenticated(state: State, session: Session): Next<State, Action> =
            next(state, Effect.send(Action.Delegate.Authenticated(session, remember = state.login.keepSignedIn)))

        return Reducer { state, action ->
            when (action) {
                is Action.Login -> {
                    val result = login.reduce(state.login, action.action)
                    val child = result.effect.map<Action> { Action.Login(it) }
                    val updated = state.copy(login = result.state)
                    val parent: Next<State, Action> = when (val delegate = action.action) {
                        is Login.Action.Delegate.Authenticated -> authenticated(updated, delegate.session)
                        is Login.Action.Delegate.UseOTP -> next(updated.copy(path = updated.path.push(Path.OTPLogin(OTPLogin.State(email = delegate.email)))))
                        Login.Action.Delegate.Register -> next(updated.copy(path = updated.path.push(Path.Register(Register.State()))))
                        is Login.Action.Delegate.ForgotPassword ->
                            next(updated.copy(path = updated.path.push(Path.ForgotPassword(ForgotPassword.State(email = delegate.email)))))
                        // No code was just sent, so resend is available at once.
                        is Login.Action.Delegate.VerifyEmail ->
                            next(updated.copy(path = updated.path.push(Path.VerifyEmail(VerifyEmail.State(email = delegate.email, resendIn = 0)))))
                        else -> next(updated)
                    }
                    next(parent.state, Effect.merge(child, parent.effect))
                }

                is Action.Element -> {
                    // The element's own reducer first, then the flow's, as TCA's `forEach` runs them.
                    val element = state.path.reduceElement(action.id, { Action.Element(action.id, it) }) { reduceScreen(it, action.action) }
                    val updated = state.copy(path = element.state)
                    val parent: Next<State, Action> = when (val child = action.action) {
                        is PathAction.OTPLogin -> when (val a = child.action) {
                            is OTPLogin.Action.Delegate.Authenticated -> authenticated(updated, a.session)
                            OTPLogin.Action.BackTapped -> pop(updated, action.id)
                            else -> next(updated)
                        }
                        is PathAction.Register -> when (val a = child.action) {
                            is Register.Action.Delegate.VerifyEmail ->
                                next(updated.copy(path = updated.path.push(Path.VerifyEmail(VerifyEmail.State(email = a.email)))))
                            is Register.Action.Delegate.Authenticated -> authenticated(updated, a.session)
                            Register.Action.BackTapped -> pop(updated, action.id)
                            else -> next(updated)
                        }
                        is PathAction.VerifyEmail -> when (val a = child.action) {
                            is VerifyEmail.Action.Delegate.Authenticated -> authenticated(updated, a.session)
                            VerifyEmail.Action.BackTapped -> pop(updated, action.id)
                            else -> next(updated)
                        }
                        is PathAction.ForgotPassword -> when (val a = child.action) {
                            is ForgotPassword.Action.Delegate.PasswordReset -> {
                                val popped = updated.path.removeAll()
                                next(
                                    updated.copy(
                                        path = popped.state,
                                        login = updated.login.copy(email = a.email, password = "", showPassword = false, error = null),
                                    ),
                                    popped.effect,
                                )
                            }
                            ForgotPassword.Action.BackTapped -> pop(updated, action.id)
                            else -> next(updated)
                        }
                    }
                    next(parent.state, Effect.merge(element.effect, parent.effect))
                }

                is Action.PopFrom -> pop(state, action.id)
                is Action.Delegate -> next(state)
            }
        }
    }

    private fun pop(state: State, id: Int): Next<State, Action> {
        val popped = state.path.popFrom(id)
        return next(state.copy(path = popped.state), popped.effect)
    }
}

/** Lifts a screen's result into a stack's path and path-action types. */
internal fun <S, A, P, PA> Next<S, A>.lift(wrap: (S) -> P, embed: (A) -> PA): Next<P, PA> = Next(wrap(state), effect.map(embed))

object AuthFlowAgent : AgentContainer<AuthFlow.State, AuthFlow.Action> {
    private const val BACK_HELP = "Go back to the previous screen."

    override fun activeScreen(state: AuthFlow.State): ActiveScreen<AuthFlow.Action> {
        val top = state.path.top ?: return LoginAgent.activeScreen(state.login).map { AuthFlow.Action.Login(it) }
        val id = top.id
        val child: ActiveScreen<AuthFlow.Action> = when (val screen = top.screen) {
            is AuthFlow.Path.OTPLogin ->
                OTPLoginAgent.activeScreen(screen.state).map { AuthFlow.Action.Element(id, AuthFlow.PathAction.OTPLogin(it)) }
            is AuthFlow.Path.Register ->
                RegisterAgent.activeScreen(screen.state).map { AuthFlow.Action.Element(id, AuthFlow.PathAction.Register(it)) }
            is AuthFlow.Path.VerifyEmail ->
                VerifyEmailAgent.activeScreen(screen.state).map { AuthFlow.Action.Element(id, AuthFlow.PathAction.VerifyEmail(it)) }
            is AuthFlow.Path.ForgotPassword ->
                ForgotPasswordAgent.activeScreen(screen.state).map { AuthFlow.Action.Element(id, AuthFlow.PathAction.ForgotPassword(it)) }
        }
        val back = AgentCommand.action<AuthFlow.State, AuthFlow.Action>("back", help = BACK_HELP, action = AuthFlow.Action.PopFrom(id))
            .resolve(state, source = "AuthFlow")
        return child.identified("#$id").appending(listOf(back))
    }

    override val registry: List<ScreenDoc>
        get() {
            val back = CommandDoc("back", null, BACK_HELP, "AuthFlow")
            val pushed = OTPLoginAgent.screenDocs + RegisterAgent.screenDocs + VerifyEmailAgent.screenDocs + ForgotPasswordAgent.screenDocs
            return LoginAgent.screenDocs + pushed.map { it.inheriting(listOf(back)) }
        }
}
