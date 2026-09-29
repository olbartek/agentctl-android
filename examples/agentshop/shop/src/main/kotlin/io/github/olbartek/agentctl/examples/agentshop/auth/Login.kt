package io.github.olbartek.agentctl.examples.agentshop.auth

import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.CommandGate
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.examples.agentshop.clients.AuthClient
import io.github.olbartek.agentctl.examples.agentshop.models.AuthError
import io.github.olbartek.agentctl.examples.agentshop.models.Outcome
import io.github.olbartek.agentctl.examples.agentshop.models.Session
import io.github.olbartek.agentctl.examples.agentshop.models.attempt
import io.github.olbartek.agentctl.examples.agentshop.models.isValidEmail
import io.github.olbartek.agentctl.next

/**
 * Email and password login, or "Sign in with Google". Links to OTP login, registration and forgot password.
 *
 * "Keep me signed in" is read by the container when any auth screen signs in: when it is off, the session is not
 * restored on the next launch. An unverified account is sent to email verification instead of signing in.
 */
object Login {
    data class State(
        val email: String = "",
        val password: String = "",
        val showPassword: Boolean = false,
        val keepSignedIn: Boolean = true,
        val isLoading: Boolean = false,
        val isGoogleLoading: Boolean = false,
        val error: AuthError? = null,
    ) {
        /** A sign-in (password or Google) is in flight. */
        val isBusy: Boolean get() = isLoading || isGoogleLoading

        /** Submit is enabled only for a valid email and a non-empty password. */
        val canSubmit: Boolean get() = isValidEmail(email) && password.isNotEmpty() && !isBusy
    }

    sealed interface Action {
        data class EmailChanged(val email: String) : Action
        data class PasswordChanged(val password: String) : Action
        data class ShowPasswordChanged(val isOn: Boolean) : Action
        data class KeepSignedInChanged(val isOn: Boolean) : Action
        data object SubmitTapped : Action
        data object GoogleTapped : Action
        data class LoginResponse(val result: Outcome<Session, AuthError>) : Action
        data object UseOTPTapped : Action
        data object RegisterTapped : Action
        data object ForgotPasswordTapped : Action

        /** What the screen tells its container. A screen never navigates by itself. */
        sealed interface Delegate : Action {
            data class Authenticated(val session: Session) : Delegate
            data class UseOTP(val email: String) : Delegate
            data object Register : Delegate
            data class ForgotPassword(val email: String) : Delegate

            /** The account exists but its email was never verified. */
            data class VerifyEmail(val email: String) : Delegate
        }
    }

    fun reducer(authClient: AuthClient): Reducer<State, Action> = Reducer { state, action ->
        when (action) {
            // A field change clears the error, as a binding does; the two switches leave it.
            is Action.EmailChanged -> next(state.copy(email = action.email, error = null))
            is Action.PasswordChanged -> next(state.copy(password = action.password, error = null))
            is Action.ShowPasswordChanged -> next(state.copy(showPassword = action.isOn))
            is Action.KeepSignedInChanged -> next(state.copy(keepSignedIn = action.isOn))

            Action.SubmitTapped -> {
                if (!state.canSubmit) return@Reducer next(state)
                val email = state.email
                val password = state.password
                next(
                    state.copy(isLoading = true, error = null),
                    Effect.run { send -> send(Action.LoginResponse(attempt(AuthError::of) { authClient.login(email, password) })) },
                )
            }

            Action.GoogleTapped -> {
                if (state.isBusy) return@Reducer next(state)
                next(
                    state.copy(isGoogleLoading = true, error = null),
                    Effect.run { send -> send(Action.LoginResponse(attempt(AuthError::of) { authClient.signInWithGoogle() })) },
                )
            }

            is Action.LoginResponse -> {
                val done = state.copy(isLoading = false, isGoogleLoading = false)
                when (val result = action.result) {
                    is Outcome.Success -> next(done, Effect.send(Action.Delegate.Authenticated(result.value)))
                    is Outcome.Failure ->
                        if (result.error == AuthError.EMAIL_NOT_VERIFIED) {
                            next(done, Effect.send(Action.Delegate.VerifyEmail(state.email)))
                        } else {
                            next(done.copy(error = result.error))
                        }
                }
            }

            Action.UseOTPTapped -> next(state, Effect.send(Action.Delegate.UseOTP(state.email)))
            Action.RegisterTapped -> next(state, Effect.send(Action.Delegate.Register))
            Action.ForgotPasswordTapped -> next(state, Effect.send(Action.Delegate.ForgotPassword(state.email)))
            is Action.Delegate -> next(state)
        }
    }
}

object LoginAgent : AgentScreen<Login.State, Login.Action> {
    override val screenName: String = "Login"
    override val screenPaths: List<String> = listOf("auth/login")

    override fun screenPath(state: Login.State): String = "auth/login"

    override val summaryKeys: List<String> = listOf("email", "keepSignedIn", "revealed", "canSubmit", "loading")

    override fun summary(state: Login.State): List<SummaryItem> = listOf(
        SummaryItem("email", state.email),
        SummaryItem("keepSignedIn", state.keepSignedIn),
        SummaryItem("revealed", if (state.showPassword) "password" else "none"),
        SummaryItem("canSubmit", state.canSubmit),
        SummaryItem("loading", state.isBusy),
    )

    override fun errorCode(state: Login.State): String? = state.error?.code

    override val commands: List<AgentCommand<Login.State, Login.Action>> = listOf(
        AgentCommand.text("email", help = "Set the email field.") { Login.Action.EmailChanged(it) },
        AgentCommand.text("password", help = "Set the password field.") { Login.Action.PasswordChanged(it) },
        AgentCommand.onOff("show-password", help = "Show or hide the password.") { Login.Action.ShowPasswordChanged(it) },
        AgentCommand.onOff(
            "keep-signed-in",
            help = "Tick \"Keep me signed in\" (on by default). Off: the session is not restored on relaunch.",
        ) { Login.Action.KeepSignedInChanged(it) },
        AgentCommand.action(
            "submit",
            help = "Log in with the email and password.",
            action = Login.Action.SubmitTapped,
            gate = CommandGate("canSubmit=false") { it.canSubmit },
        ),
        AgentCommand.action(
            "google",
            help = "Sign in with Google (the mock signs in as becca@gmail.com).",
            action = Login.Action.GoogleTapped,
            gate = CommandGate("loading=true") { !it.isBusy },
        ),
        AgentCommand.action("use-otp", help = "Switch to login with a one-time code (keeps the email).", action = Login.Action.UseOTPTapped),
        AgentCommand.action("register", help = "Open registration (\"Sign up here\").", action = Login.Action.RegisterTapped),
        AgentCommand.action("forgot", help = "Open forgot password (keeps the email).", action = Login.Action.ForgotPasswordTapped),
    )
}
