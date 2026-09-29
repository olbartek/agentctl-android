package io.github.olbartek.agentctl.examples.agentshop.auth

import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.CommandGate
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.examples.agentshop.clients.AuthClient
import io.github.olbartek.agentctl.examples.agentshop.models.AuthError
import io.github.olbartek.agentctl.examples.agentshop.models.Coded
import io.github.olbartek.agentctl.examples.agentshop.models.Graphemes
import io.github.olbartek.agentctl.examples.agentshop.models.Outcome
import io.github.olbartek.agentctl.examples.agentshop.models.ValidationIssue
import io.github.olbartek.agentctl.examples.agentshop.models.attempt
import io.github.olbartek.agentctl.examples.agentshop.models.digitsPrefix
import io.github.olbartek.agentctl.examples.agentshop.models.isStrongPassword
import io.github.olbartek.agentctl.examples.agentshop.models.isValidEmail
import io.github.olbartek.agentctl.examples.agentshop.models.passwordIssues
import io.github.olbartek.agentctl.next

/**
 * Forgot password: request a reset code, then enter the code with a new password, then go back to login.
 *
 * Requesting always succeeds, so the app never reveals which accounts exist.
 */
object ForgotPassword {
    enum class Step(override val code: String) : Coded {
        EMAIL("email"),
        RESET("reset"),
        DONE("done"),
    }

    data class State(
        val step: Step = Step.EMAIL,
        val email: String = "",
        val code: String = "",
        val password: String = "",
        val confirm: String = "",
        val showPassword: Boolean = false,
        val showConfirm: Boolean = false,
        val isLoading: Boolean = false,
        val error: AuthError? = null,
    ) {
        val canSend: Boolean get() = isValidEmail(email) && !isLoading

        val issues: List<ValidationIssue>
            get() {
                val issues = mutableListOf<ValidationIssue>()
                if (password.isNotEmpty()) passwordIssues(password).mapTo(issues, ValidationIssue::of)
                if (confirm.isNotEmpty() && confirm != password) issues.add(ValidationIssue.CONFIRM_MISMATCH)
                return issues
            }

        /** Issues shown under the new-password and confirm fields. */
        val passwordFieldIssues: List<ValidationIssue> get() = issues.filter { it != ValidationIssue.CONFIRM_MISMATCH }
        val confirmFieldIssues: List<ValidationIssue> get() = issues.filter { it == ValidationIssue.CONFIRM_MISMATCH }

        val canSubmit: Boolean
            get() = Graphemes.count(code) == 6 && isStrongPassword(password) && confirm == password && !isLoading
    }

    sealed interface Action {
        data class EmailChanged(val email: String) : Action

        /** Keeps the first six digits of what is typed. */
        data class CodeChanged(val code: String) : Action
        data class PasswordChanged(val password: String) : Action
        data class ConfirmChanged(val confirm: String) : Action
        data class ShowPasswordChanged(val isOn: Boolean) : Action
        data class ShowConfirmChanged(val isOn: Boolean) : Action
        data object SendTapped : Action
        data class RequestResponse(val error: AuthError?) : Action
        data object SubmitTapped : Action
        data class ResetResponse(val error: AuthError?) : Action
        data object BackToLoginTapped : Action

        /** The back button and "Login to your account": the container pops the screen. */
        data object BackTapped : Action

        /** What the screen tells its container. */
        sealed interface Delegate : Action {
            /** The password was reset; go back to login with this email prefilled. */
            data class PasswordReset(val email: String) : Delegate
        }
    }

    fun reducer(authClient: AuthClient): Reducer<State, Action> = Reducer { state, action ->
        when (action) {
            is Action.EmailChanged -> next(state.copy(email = action.email, error = null))
            is Action.CodeChanged -> next(state.copy(code = action.code.digitsPrefix(6), error = null))
            is Action.PasswordChanged -> next(state.copy(password = action.password, error = null))
            is Action.ConfirmChanged -> next(state.copy(confirm = action.confirm, error = null))
            is Action.ShowPasswordChanged -> next(state.copy(showPassword = action.isOn))
            is Action.ShowConfirmChanged -> next(state.copy(showConfirm = action.isOn))

            Action.SendTapped -> {
                if (!state.canSend) return@Reducer next(state)
                val email = state.email
                next(
                    state.copy(isLoading = true, error = null),
                    Effect.run { send ->
                        val result = attempt(AuthError::of) { authClient.requestPasswordReset(email) }
                        send(Action.RequestResponse((result as? Outcome.Failure)?.error))
                    },
                )
            }

            is Action.RequestResponse -> {
                val error = action.error
                if (error != null) next(state.copy(isLoading = false, error = error)) else next(state.copy(isLoading = false, step = Step.RESET))
            }

            Action.SubmitTapped -> {
                if (!state.canSubmit) return@Reducer next(state)
                val email = state.email
                val code = state.code
                val password = state.password
                next(
                    state.copy(isLoading = true, error = null),
                    Effect.run { send ->
                        val result = attempt(AuthError::of) { authClient.resetPassword(email, code, password) }
                        send(Action.ResetResponse((result as? Outcome.Failure)?.error))
                    },
                )
            }

            is Action.ResetResponse -> {
                val error = action.error
                if (error != null) {
                    next(state.copy(isLoading = false, error = error))
                } else {
                    next(
                        state.copy(
                            isLoading = false,
                            step = Step.DONE,
                            code = "",
                            password = "",
                            confirm = "",
                            showPassword = false,
                            showConfirm = false,
                        ),
                    )
                }
            }

            Action.BackToLoginTapped -> next(state, Effect.send(Action.Delegate.PasswordReset(state.email)))
            Action.BackTapped, is Action.Delegate -> next(state)
        }
    }
}

object ForgotPasswordAgent : AgentScreen<ForgotPassword.State, ForgotPassword.Action> {
    private val emailStep = listOf("auth/forgot/email")
    private val resetStep = listOf("auth/forgot/reset")
    private val doneStep = listOf("auth/forgot/done")

    override val screenPaths: List<String> = listOf("auth/forgot/email", "auth/forgot/reset", "auth/forgot/done")

    override fun screenPath(state: ForgotPassword.State): String = "auth/forgot/${state.step.code}"

    override val summaryKeys: List<String> = listOf("email", "canSend", "revealed", "issues", "canSubmit", "loading")

    override fun summary(state: ForgotPassword.State): List<SummaryItem> = when (state.step) {
        ForgotPassword.Step.EMAIL -> listOf(
            SummaryItem("email", state.email),
            SummaryItem("canSend", state.canSend),
            SummaryItem("loading", state.isLoading),
        )
        ForgotPassword.Step.RESET -> listOf(
            SummaryItem("email", state.email),
            SummaryItem("revealed", revealed(state.showPassword, state.showConfirm)),
            SummaryItem("issues", ValidationIssue.summary(state.issues)),
            SummaryItem("canSubmit", state.canSubmit),
            SummaryItem("loading", state.isLoading),
        )
        ForgotPassword.Step.DONE -> listOf(SummaryItem("email", state.email))
    }

    override fun errorCode(state: ForgotPassword.State): String? = state.error?.code

    override val commands: List<AgentCommand<ForgotPassword.State, ForgotPassword.Action>> = listOf(
        AgentCommand.text("email", help = "Set the email field.", paths = emailStep) { ForgotPassword.Action.EmailChanged(it) },
        AgentCommand.action(
            "send",
            help = "Request a reset code (always succeeds).",
            action = ForgotPassword.Action.SendTapped,
            paths = emailStep,
            gate = CommandGate("canSend=false") { it.canSend },
        ),
        AgentCommand.text("code", help = "Type the 6-digit reset code.", argument = "<digits>", paths = resetStep) {
            ForgotPassword.Action.CodeChanged(it)
        },
        AgentCommand.text("password", help = "Set the new password.", paths = resetStep) { ForgotPassword.Action.PasswordChanged(it) },
        AgentCommand.text("confirm", help = "Confirm the new password.", paths = resetStep) { ForgotPassword.Action.ConfirmChanged(it) },
        AgentCommand.onOff("show-password", help = "Show or hide the new password.", paths = resetStep) {
            ForgotPassword.Action.ShowPasswordChanged(it)
        },
        AgentCommand.onOff("show-confirm", help = "Show or hide the confirm field.", paths = resetStep) {
            ForgotPassword.Action.ShowConfirmChanged(it)
        },
        AgentCommand.action(
            "submit",
            help = "Reset the password.",
            action = ForgotPassword.Action.SubmitTapped,
            paths = resetStep,
            gate = CommandGate("canSubmit=false") { it.canSubmit },
        ),
        AgentCommand.action(
            "to-login",
            help = "Go back to login with the email prefilled.",
            action = ForgotPassword.Action.BackToLoginTapped,
            paths = doneStep,
        ),
    )
}
