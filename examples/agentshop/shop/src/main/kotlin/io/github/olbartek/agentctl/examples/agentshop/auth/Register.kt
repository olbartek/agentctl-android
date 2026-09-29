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
import io.github.olbartek.agentctl.examples.agentshop.models.ValidationIssue
import io.github.olbartek.agentctl.examples.agentshop.models.attempt
import io.github.olbartek.agentctl.examples.agentshop.models.isStrongPassword
import io.github.olbartek.agentctl.examples.agentshop.models.isValidEmail
import io.github.olbartek.agentctl.examples.agentshop.models.passwordIssues
import io.github.olbartek.agentctl.examples.agentshop.models.trimmingWhitespaces
import io.github.olbartek.agentctl.next

/**
 * Create an account ("Signup"). Validation issues are shown inline. A successful registration emails a code and
 * moves on to email verification; "Sign up with Google" signs in directly.
 */
object Register {
    data class State(
        val name: String = "",
        val email: String = "",
        val password: String = "",
        val confirm: String = "",
        val showPassword: Boolean = false,
        val showConfirm: Boolean = false,
        val acceptedTerms: Boolean = false,
        val isLoading: Boolean = false,
        val isGoogleLoading: Boolean = false,
        val error: AuthError? = null,
    ) {
        /** Validation problems for fields that are not empty, e.g. `email`, `passwordTooShort`, `confirmMismatch`. */
        val issues: List<ValidationIssue>
            get() {
                val issues = mutableListOf<ValidationIssue>()
                if (email.isNotEmpty() && !isValidEmail(email)) issues.add(ValidationIssue.EMAIL)
                if (password.isNotEmpty()) passwordIssues(password).mapTo(issues, ValidationIssue::of)
                if (confirm.isNotEmpty() && confirm != password) issues.add(ValidationIssue.CONFIRM_MISMATCH)
                return issues
            }

        /** Issues shown under the email, password and confirm fields. */
        val emailFieldIssues: List<ValidationIssue> get() = issues.filter { it == ValidationIssue.EMAIL }
        val passwordFieldIssues: List<ValidationIssue>
            get() = issues.filter { it != ValidationIssue.EMAIL && it != ValidationIssue.CONFIRM_MISMATCH }
        val confirmFieldIssues: List<ValidationIssue> get() = issues.filter { it == ValidationIssue.CONFIRM_MISMATCH }

        /** A sign-up (form or Google) is in flight. */
        val isBusy: Boolean get() = isLoading || isGoogleLoading

        val canSubmit: Boolean
            get() = name.trimmingWhitespaces().isNotEmpty() && isValidEmail(email) && isStrongPassword(password) &&
                confirm == password && acceptedTerms && !isBusy
    }

    sealed interface Action {
        data class NameChanged(val name: String) : Action
        data class EmailChanged(val email: String) : Action
        data class PasswordChanged(val password: String) : Action
        data class ConfirmChanged(val confirm: String) : Action
        data class ShowPasswordChanged(val isOn: Boolean) : Action
        data class ShowConfirmChanged(val isOn: Boolean) : Action
        data class TermsChanged(val isOn: Boolean) : Action
        data object SubmitTapped : Action
        data class RegisterResponse(val error: AuthError?) : Action
        data object GoogleTapped : Action
        data class GoogleResponse(val result: Outcome<Session, AuthError>) : Action

        /** The back button and "Sign in here": the container pops the screen. */
        data object BackTapped : Action

        /** What the screen tells its container. */
        sealed interface Delegate : Action {
            /** The account was created; verify the emailed code next. */
            data class VerifyEmail(val email: String) : Delegate

            /** Signed up with Google. */
            data class Authenticated(val session: Session) : Delegate
        }
    }

    fun reducer(authClient: AuthClient): Reducer<State, Action> = Reducer { state, action ->
        when (action) {
            is Action.NameChanged -> next(state.copy(name = action.name, error = null))
            is Action.EmailChanged -> next(state.copy(email = action.email, error = null))
            is Action.PasswordChanged -> next(state.copy(password = action.password, error = null))
            is Action.ConfirmChanged -> next(state.copy(confirm = action.confirm, error = null))
            is Action.TermsChanged -> next(state.copy(acceptedTerms = action.isOn, error = null))
            is Action.ShowPasswordChanged -> next(state.copy(showPassword = action.isOn))
            is Action.ShowConfirmChanged -> next(state.copy(showConfirm = action.isOn))

            Action.SubmitTapped -> {
                if (!state.canSubmit) return@Reducer next(state)
                val name = state.name
                val email = state.email
                val password = state.password
                next(
                    state.copy(isLoading = true, error = null),
                    Effect.run { send ->
                        val result = attempt(AuthError::of) { authClient.register(name, email, password) }
                        send(Action.RegisterResponse((result as? Outcome.Failure)?.error))
                    },
                )
            }

            is Action.RegisterResponse -> {
                val error = action.error
                if (error != null) {
                    next(state.copy(isLoading = false, error = error))
                } else {
                    next(state.copy(isLoading = false), Effect.send(Action.Delegate.VerifyEmail(state.email)))
                }
            }

            Action.GoogleTapped -> {
                if (state.isBusy) return@Reducer next(state)
                next(
                    state.copy(isGoogleLoading = true, error = null),
                    Effect.run { send -> send(Action.GoogleResponse(attempt(AuthError::of) { authClient.signInWithGoogle() })) },
                )
            }

            is Action.GoogleResponse -> when (val result = action.result) {
                is Outcome.Success ->
                    next(state.copy(isGoogleLoading = false), Effect.send(Action.Delegate.Authenticated(result.value)))
                is Outcome.Failure -> next(state.copy(isGoogleLoading = false, error = result.error))
            }

            Action.BackTapped, is Action.Delegate -> next(state)
        }
    }
}

object RegisterAgent : AgentScreen<Register.State, Register.Action> {
    override val screenPaths: List<String> = listOf("auth/register")

    override fun screenPath(state: Register.State): String = "auth/register"

    override val summaryKeys: List<String> = listOf("email", "terms", "revealed", "issues", "canSubmit", "loading")

    override fun summary(state: Register.State): List<SummaryItem> = listOf(
        SummaryItem("email", state.email),
        SummaryItem("terms", state.acceptedTerms),
        SummaryItem("revealed", revealed(state.showPassword, state.showConfirm)),
        SummaryItem("issues", ValidationIssue.summary(state.issues)),
        SummaryItem("canSubmit", state.canSubmit),
        SummaryItem("loading", state.isBusy),
    )

    override fun errorCode(state: Register.State): String? = state.error?.code

    override val commands: List<AgentCommand<Register.State, Register.Action>> = listOf(
        AgentCommand.text("name", help = "Set the full name field.") { Register.Action.NameChanged(it) },
        AgentCommand.text("email", help = "Set the email field.") { Register.Action.EmailChanged(it) },
        AgentCommand.text("password", help = "Set the password field.") { Register.Action.PasswordChanged(it) },
        AgentCommand.text("confirm", help = "Set the confirm-password field.") { Register.Action.ConfirmChanged(it) },
        AgentCommand.onOff("show-password", help = "Show or hide the password.") { Register.Action.ShowPasswordChanged(it) },
        AgentCommand.onOff("show-confirm", help = "Show or hide the confirm-password field.") { Register.Action.ShowConfirmChanged(it) },
        AgentCommand.onOff("terms", help = "Accept or decline the terms.") { Register.Action.TermsChanged(it) },
        AgentCommand.action(
            "submit",
            help = "Create the account; a verification code is emailed (verify it next).",
            action = Register.Action.SubmitTapped,
            gate = CommandGate("canSubmit=false") { it.canSubmit },
        ),
        AgentCommand.action(
            "google",
            help = "Sign up with Google (the mock signs in as becca@gmail.com).",
            action = Register.Action.GoogleTapped,
            gate = CommandGate("loading=true") { !it.isBusy },
        ),
    )
}

/** Which password fields show their text: `none`, `password`, `confirm` or `password,confirm`. */
internal fun revealed(password: Boolean, confirm: Boolean): String {
    val fields = listOfNotNull(if (password) "password" else null, if (confirm) "confirm" else null)
    return if (fields.isEmpty()) "none" else fields.joinToString(",")
}
