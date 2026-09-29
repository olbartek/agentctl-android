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
import io.github.olbartek.agentctl.examples.agentshop.models.Graphemes
import io.github.olbartek.agentctl.examples.agentshop.models.Outcome
import io.github.olbartek.agentctl.examples.agentshop.models.Session
import io.github.olbartek.agentctl.examples.agentshop.models.attempt
import io.github.olbartek.agentctl.examples.agentshop.models.digitsPrefix
import io.github.olbartek.agentctl.next
import kotlin.time.Duration.Companion.seconds

/**
 * Verify a new account's email: enter the 6-digit code that registration emailed ("Create Account").
 *
 * Resend is blocked for 30 s after each email; the countdown starts when the screen appears. Opened from login for an
 * unverified account, no code was just sent, so resend is available at once.
 */
object VerifyEmail {
    const val RESEND_COOLDOWN: Int = 30

    data class State(
        val email: String,
        val code: String = "",
        val resendIn: Int = RESEND_COOLDOWN,
        val isLoading: Boolean = false,
        val error: AuthError? = null,
    ) {
        val canVerify: Boolean get() = Graphemes.count(code) == 6 && !isLoading
    }

    sealed interface Action {
        /** Keeps the first six digits of what is typed. */
        data class CodeChanged(val code: String) : Action
        data object OnAppear : Action
        data object Tick : Action
        data object VerifyTapped : Action
        data class VerifyResponse(val result: Outcome<Session, AuthError>) : Action
        data object ResendTapped : Action
        data class ResendResponse(val error: AuthError?) : Action

        /** The back button: the container pops the screen. */
        data object BackTapped : Action

        /** What the screen tells its container. */
        sealed interface Delegate : Action {
            data class Authenticated(val session: Session) : Delegate
        }
    }

    private object CountdownId

    fun reducer(authClient: AuthClient, clock: AgentClock): Reducer<State, Action> {
        fun countdown(): Effect<Action> =
            Effect.run<Action> { send -> clock.every(1.seconds) { send(Action.Tick) } }.cancellable(CountdownId, cancelInFlight = true)

        return Reducer { state, action ->
            when (action) {
                is Action.CodeChanged -> next(state.copy(code = action.code.digitsPrefix(6), error = null))

                Action.OnAppear -> next(state, if (state.resendIn > 0) countdown() else Effect.None)

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
                        Effect.run { send -> send(Action.VerifyResponse(attempt(AuthError::of) { authClient.verifyEmail(email, code) })) },
                    )
                }

                is Action.VerifyResponse -> when (val result = action.result) {
                    is Outcome.Success -> next(
                        state.copy(isLoading = false),
                        Effect.merge(Effect.cancel(CountdownId), Effect.send(Action.Delegate.Authenticated(result.value))),
                    )
                    is Outcome.Failure -> {
                        val failed = state.copy(isLoading = false, error = result.error)
                        next(if (result.error == AuthError.INVALID_CODE) failed.copy(code = "") else failed)
                    }
                }

                Action.ResendTapped -> {
                    if (state.resendIn != 0) return@Reducer next(state.copy(error = AuthError.RESEND_NOT_AVAILABLE))
                    val email = state.email
                    next(
                        state.copy(isLoading = true, error = null),
                        Effect.run { send ->
                            val result = attempt(AuthError::of) { authClient.resendVerification(email) }
                            send(Action.ResendResponse((result as? Outcome.Failure)?.error))
                        },
                    )
                }

                is Action.ResendResponse -> {
                    val error = action.error
                    if (error != null) {
                        next(state.copy(isLoading = false, error = error))
                    } else {
                        next(state.copy(isLoading = false, code = "", resendIn = RESEND_COOLDOWN), countdown())
                    }
                }

                Action.BackTapped, is Action.Delegate -> next(state)
            }
        }
    }
}

object VerifyEmailAgent : AgentScreen<VerifyEmail.State, VerifyEmail.Action> {
    override val screenPaths: List<String> = listOf("auth/register/verify")

    override fun screenPath(state: VerifyEmail.State): String = "auth/register/verify"

    override val summaryKeys: List<String> = listOf("email", "resendIn", "canVerify", "loading")

    override fun summary(state: VerifyEmail.State): List<SummaryItem> = listOf(
        SummaryItem("email", state.email),
        SummaryItem("resendIn", state.resendIn),
        SummaryItem("canVerify", state.canVerify),
        SummaryItem("loading", state.isLoading),
    )

    override fun errorCode(state: VerifyEmail.State): String? = state.error?.code

    override val onAppear: VerifyEmail.Action = VerifyEmail.Action.OnAppear

    override val commands: List<AgentCommand<VerifyEmail.State, VerifyEmail.Action>> = listOf(
        AgentCommand.text("code", help = "Type the 6-digit verification code.", argument = "<digits>") {
            VerifyEmail.Action.CodeChanged(it)
        },
        AgentCommand.action(
            "verify",
            help = "Verify the email and sign in (\"Create Account\").",
            action = VerifyEmail.Action.VerifyTapped,
            gate = CommandGate("canVerify=false") { it.canVerify },
        ),
        // Not gated on purpose: while `resendIn > 0` the reducer answers with `error=resendNotAvailable`.
        AgentCommand.action("resend", help = "Email a new code (blocked while resendIn > 0).", action = VerifyEmail.Action.ResendTapped),
    )
}
