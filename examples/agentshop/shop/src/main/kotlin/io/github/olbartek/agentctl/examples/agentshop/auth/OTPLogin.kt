package io.github.olbartek.agentctl.examples.agentshop.auth

import io.github.olbartek.agentctl.AgentClock
import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.CommandGate
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.every
import io.github.olbartek.agentctl.examples.agentshop.clients.AuthClient
import io.github.olbartek.agentctl.examples.agentshop.models.AuthError
import io.github.olbartek.agentctl.examples.agentshop.models.Coded
import io.github.olbartek.agentctl.examples.agentshop.models.Graphemes
import io.github.olbartek.agentctl.examples.agentshop.models.Outcome
import io.github.olbartek.agentctl.examples.agentshop.models.Session
import io.github.olbartek.agentctl.examples.agentshop.models.attempt
import io.github.olbartek.agentctl.examples.agentshop.models.digitsPrefix
import io.github.olbartek.agentctl.examples.agentshop.models.isValidEmail
import io.github.olbartek.agentctl.next
import kotlin.time.Duration.Companion.seconds

/**
 * Login with a one-time code sent by email: enter the email, then the 6-digit code.
 *
 * Resend is blocked for 30 s after each send (`resendIn` counts down on the app's clock). After 3 wrong codes,
 * verifying is disabled until a resend.
 */
object OTPLogin {
    const val RESEND_COOLDOWN: Int = 30
    const val MAX_ATTEMPTS: Int = 3

    enum class Step(override val code: String) : Coded {
        EMAIL("email"),
        CODE("code"),
    }

    data class State(
        val step: Step = Step.EMAIL,
        val email: String = "",
        val code: String = "",
        val resendIn: Int = 0,
        val attemptsLeft: Int = MAX_ATTEMPTS,
        val isLoading: Boolean = false,
        val error: AuthError? = null,
    ) {
        val canSend: Boolean get() = isValidEmail(email) && !isLoading
        val canVerify: Boolean get() = Graphemes.count(code) == 6 && attemptsLeft > 0 && !isLoading
    }

    sealed interface Action {
        data class EmailChanged(val email: String) : Action

        /** Keeps the first six digits of what is typed. */
        data class CodeChanged(val code: String) : Action
        data object SendTapped : Action
        data object ResendTapped : Action
        data class SendResponse(val error: AuthError?) : Action
        data object Tick : Action
        data object VerifyTapped : Action
        data class VerifyResponse(val result: Outcome<Session, AuthError>) : Action

        /** The back button and "Login with password": the container pops the screen. */
        data object BackTapped : Action

        /** What the screen tells its container. */
        sealed interface Delegate : Action {
            data class Authenticated(val session: Session) : Delegate
        }
    }

    private object CountdownId

    fun reducer(authClient: AuthClient, clock: AgentClock): Reducer<State, Action> = Reducer { state, action ->
        fun send(state: State): io.github.olbartek.agentctl.Next<State, Action> {
            val email = state.email
            return next(
                state.copy(isLoading = true, error = null),
                Effect.run { send ->
                    val result = attempt(AuthError::of) { authClient.sendOTP(email) }
                    send(Action.SendResponse((result as? Outcome.Failure)?.error))
                },
            )
        }

        when (action) {
            is Action.EmailChanged -> next(state.copy(email = action.email, error = null))
            is Action.CodeChanged -> next(state.copy(code = action.code.digitsPrefix(6), error = null))

            Action.SendTapped -> if (state.canSend) send(state) else next(state)

            Action.ResendTapped ->
                if (state.resendIn != 0) next(state.copy(error = AuthError.RESEND_NOT_AVAILABLE)) else send(state)

            is Action.SendResponse -> {
                val error = action.error
                if (error != null) return@Reducer next(state.copy(isLoading = false, error = error))
                next(
                    state.copy(isLoading = false, step = Step.CODE, code = "", attemptsLeft = MAX_ATTEMPTS, resendIn = RESEND_COOLDOWN),
                    Effect.run<Action> { send -> clock.every(1.seconds) { send(Action.Tick) } }
                        .cancellable(CountdownId, cancelInFlight = true),
                )
            }

            Action.Tick -> {
                val resendIn = maxOf(0, state.resendIn - 1)
                next(state.copy(resendIn = resendIn), if (resendIn == 0) Effect.cancel(CountdownId) else Effect.None)
            }

            Action.VerifyTapped -> {
                if (!state.canVerify) return@Reducer next(state)
                val email = state.email
                val code = state.code
                next(
                    state.copy(isLoading = true, error = null),
                    Effect.run { send -> send(Action.VerifyResponse(attempt(AuthError::of) { authClient.verifyOTP(email, code) })) },
                )
            }

            is Action.VerifyResponse -> when (val result = action.result) {
                is Outcome.Success -> next(
                    state.copy(isLoading = false),
                    Effect.merge(Effect.cancel(CountdownId), Effect.send(Action.Delegate.Authenticated(result.value))),
                )
                is Outcome.Failure -> {
                    val failed = state.copy(isLoading = false, error = result.error)
                    if (result.error == AuthError.INVALID_CODE) {
                        next(failed.copy(attemptsLeft = maxOf(0, state.attemptsLeft - 1), code = ""))
                    } else {
                        next(failed)
                    }
                }
            }

            Action.BackTapped, is Action.Delegate -> next(state)
        }
    }
}

object OTPLoginAgent : AgentScreen<OTPLogin.State, OTPLogin.Action> {
    override val screenPaths: List<String> = listOf("auth/otp/email", "auth/otp/code")

    override fun screenPath(state: OTPLogin.State): String = "auth/otp/${state.step.code}"

    override val summaryKeys: List<String> = listOf("email", "canSend", "resendIn", "attemptsLeft", "canVerify", "loading")

    override fun summary(state: OTPLogin.State): List<SummaryItem> = when (state.step) {
        OTPLogin.Step.EMAIL -> listOf(
            SummaryItem("email", state.email),
            SummaryItem("canSend", state.canSend),
            SummaryItem("loading", state.isLoading),
        )
        OTPLogin.Step.CODE -> listOf(
            SummaryItem("email", state.email),
            SummaryItem("resendIn", state.resendIn),
            SummaryItem("attemptsLeft", state.attemptsLeft),
            SummaryItem("canVerify", state.canVerify),
            SummaryItem("loading", state.isLoading),
        )
    }

    override fun errorCode(state: OTPLogin.State): String? = state.error?.code

    override val commands: List<AgentCommand<OTPLogin.State, OTPLogin.Action>> = listOf(
        AgentCommand.text("email", help = "Set the email field.", paths = listOf("auth/otp/email")) { OTPLogin.Action.EmailChanged(it) },
        AgentCommand.action(
            "send",
            help = "Email a one-time code.",
            action = OTPLogin.Action.SendTapped,
            paths = listOf("auth/otp/email"),
            gate = CommandGate("canSend=false") { it.canSend },
        ),
        AgentCommand.text("code", help = "Type the 6-digit code.", argument = "<digits>", paths = listOf("auth/otp/code")) {
            OTPLogin.Action.CodeChanged(it)
        },
        AgentCommand.action(
            "verify",
            help = "Verify the code and log in.",
            action = OTPLogin.Action.VerifyTapped,
            paths = listOf("auth/otp/code"),
            gate = CommandGate("canVerify=false") { it.canVerify },
        ),
        // Not gated on purpose: while `resendIn > 0` the reducer answers with `error=resendNotAvailable`.
        AgentCommand.action(
            "resend",
            help = "Send a new code (blocked while resendIn > 0).",
            action = OTPLogin.Action.ResendTapped,
            paths = listOf("auth/otp/code"),
        ),
    )
}
