package io.github.olbartek.agentctl.examples.agentshop.auth

import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.examples.agentshop.TestStore
import io.github.olbartek.agentctl.examples.agentshop.TestTime
import io.github.olbartek.agentctl.examples.agentshop.clients.AuthClient
import io.github.olbartek.agentctl.examples.agentshop.clients.MockAccounts
import io.github.olbartek.agentctl.examples.agentshop.models.AuthError
import io.github.olbartek.agentctl.examples.agentshop.models.AuthException
import io.github.olbartek.agentctl.examples.agentshop.models.ValidationIssue
import io.github.olbartek.agentctl.examples.agentshop.navigation.Stack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val alice = MockAccounts.session(MockAccounts.alice.user)
private val becca = MockAccounts.session(MockAccounts.google.user)

private fun fail(error: AuthError): Nothing = throw AuthException(error)

class LoginTest {
    private fun store(state: Login.State = Login.State(), client: AuthClient = AuthClient()) =
        TestStore(state, reducer = Login.reducer(client))

    @Test
    fun canSubmitNeedsAValidEmailAndAPassword() {
        assertFalse(Login.State().canSubmit)
        assertFalse(Login.State(email = "alice@example.com").canSubmit)
        assertFalse(Login.State(email = "alice", password = "x").canSubmit)
        assertTrue(Login.State(email = "alice@example.com", password = "x").canSubmit)
        assertFalse(Login.State(email = "alice@example.com", password = "x", isLoading = true).canSubmit)
    }

    @Test
    fun happyPath() {
        val store = store(Login.State(email = "alice@example.com", password = "Passw0rd!"), AuthClient(login = { _, _ -> alice }))
        store.send(Login.Action.SubmitTapped)
        assertFalse(store.state.isLoading)
        assertEquals(Login.Action.Delegate.Authenticated(alice), store.received.last())
    }

    @Test
    fun googleSignInAndFailure() {
        val signedIn = store(client = AuthClient(signInWithGoogle = { becca })).send(Login.Action.GoogleTapped)
        assertFalse(signedIn.state.isGoogleLoading)
        assertEquals(Login.Action.Delegate.Authenticated(becca), signedIn.received.last())

        val failed = store(client = AuthClient(signInWithGoogle = { fail(AuthError.NETWORK) })).send(Login.Action.GoogleTapped)
        assertEquals(Login.State(error = AuthError.NETWORK), failed.state)
    }

    @Test
    fun googleIsIgnoredWhileASignInIsInFlight() {
        val store = store(Login.State(isLoading = true)).send(Login.Action.GoogleTapped)
        assertEquals(Login.State(isLoading = true), store.state)
    }

    /** An account that registered but never verified its email goes to verification instead of an error. */
    @Test
    fun unverifiedAccountGoesToVerification() {
        val store = store(Login.State(email = "carol@example.com", password = "Secret123"), AuthClient(login = { _, _ -> fail(AuthError.EMAIL_NOT_VERIFIED) }))
        store.send(Login.Action.SubmitTapped)
        assertNull(store.state.error)
        assertEquals(Login.Action.Delegate.VerifyEmail("carol@example.com"), store.received.last())
    }

    @Test
    fun failuresAreShownAndEditingClearsThem() {
        for (error in listOf(AuthError.INVALID_CREDENTIALS, AuthError.ACCOUNT_LOCKED, AuthError.NETWORK)) {
            val store = store(Login.State(email = "alice@example.com", password = "nope"), AuthClient(login = { _, _ -> fail(error) }))
            store.send(Login.Action.SubmitTapped)
            assertEquals(Login.State(email = "alice@example.com", password = "nope", error = error), store.state)
            // The switches keep the error; typing clears it.
            store.send(Login.Action.ShowPasswordChanged(true)).send(Login.Action.KeepSignedInChanged(false))
            assertEquals(error, store.state.error)
            store.send(Login.Action.PasswordChanged("y"))
            assertNull(store.state.error)
        }
    }

    @Test
    fun submitIsIgnoredWhenInvalid() {
        // The default client fails any call, so a submit that went through would show an error.
        val store = store(Login.State(email = "not-an-email", password = "x")).send(Login.Action.SubmitTapped)
        assertEquals(Login.State(email = "not-an-email", password = "x"), store.state)
    }

    @Test
    fun navigationDelegatesCarryTheEmail() {
        val store = store(Login.State(email = "alice@example.com"))
        store.send(Login.Action.UseOTPTapped).send(Login.Action.RegisterTapped).send(Login.Action.ForgotPasswordTapped)
        assertEquals(
            listOf(
                Login.Action.Delegate.UseOTP("alice@example.com"),
                Login.Action.Delegate.Register,
                Login.Action.Delegate.ForgotPassword("alice@example.com"),
            ),
            store.received.filterIsInstance<Login.Action.Delegate>(),
        )
    }

    @Test
    fun agentCommands() {
        val screen = LoginAgent.activeScreen(Login.State(email = "alice@example.com"))
        assertEquals("auth/login", screen.path)
        assertEquals(
            listOf("email", "password", "show-password", "keep-signed-in", "submit", "google", "use-otp", "register", "forgot"),
            screen.commands.map { it.name },
        )
        assertEquals("canSubmit=false", screen.command("submit")?.disabledReason)
        assertNull(screen.command("google")?.disabledReason)
        assertEquals(LoginAgent.summaryKeys, screen.summary.map { it.key })
        assertTrue(SummaryItem("keepSignedIn", true) in screen.summary)
        val revealed = LoginAgent.activeScreen(Login.State(showPassword = true, isGoogleLoading = true))
        assertTrue(SummaryItem("revealed", "password") in revealed.summary)
        assertTrue(SummaryItem("loading", true) in revealed.summary)
        assertEquals("loading=true", revealed.command("google")?.disabledReason)
        assertEquals("accountLocked", LoginAgent.activeScreen(Login.State(error = AuthError.ACCOUNT_LOCKED)).errorCode)
    }
}

class OTPLoginTest {
    /** Send, blocked resend, the full 30 s countdown, a real resend, then a successful verify (which cancels the countdown). */
    @Test
    fun sendCountdownResendAndVerify() {
        val client = AuthClient(
            sendOTP = {},
            verifyOTP = { _, code -> if (code == "123456") alice else fail(AuthError.INVALID_CODE) },
        )
        val time = TestTime()
        // The reducer's clock sleeps on the store's own virtual-time dispatcher.
        val timed = TestStore(OTPLogin.State(email = "alice@example.com"), time, OTPLogin.reducer(client, time.clock))
        timed.send(OTPLogin.Action.SendTapped)
        assertEquals(OTPLogin.State(step = OTPLogin.Step.CODE, email = "alice@example.com", resendIn = 30), timed.state)
        assertEquals(1, timed.pending)

        timed.send(OTPLogin.Action.ResendTapped)
        assertEquals(AuthError.RESEND_NOT_AVAILABLE, timed.state.error)

        timed.advance(10.seconds)
        assertEquals(20, timed.state.resendIn)
        timed.advance(20.seconds)
        assertEquals(0, timed.state.resendIn)
        assertEquals(0, timed.pending, "the countdown stops at zero")
        assertEquals(30, timed.received.count { it == OTPLogin.Action.Tick })

        timed.send(OTPLogin.Action.ResendTapped)
        assertEquals(30, timed.state.resendIn)
        assertNull(timed.state.error)

        timed.send(OTPLogin.Action.CodeChanged("12-34 56"))
        assertEquals("123456", timed.state.code)
        timed.send(OTPLogin.Action.VerifyTapped)
        assertEquals(OTPLogin.Action.Delegate.Authenticated(alice), timed.received.last())
        assertEquals(0, timed.pending, "a successful verify cancels the countdown")
    }

    @Test
    fun sendFailures() {
        for (error in listOf(AuthError.UNKNOWN_EMAIL, AuthError.NETWORK)) {
            val time = TestTime()
            val store = TestStore(OTPLogin.State(email = "nobody@example.com"), time, OTPLogin.reducer(AuthClient(sendOTP = { fail(error) }), time.clock))
            store.send(OTPLogin.Action.SendTapped)
            assertEquals(OTPLogin.State(email = "nobody@example.com", error = error), store.state)
        }
    }

    @Test
    fun threeWrongCodesRequireAResend() {
        val time = TestTime()
        val client = AuthClient(verifyOTP = { _, _ -> fail(AuthError.INVALID_CODE) })
        val store = TestStore(OTPLogin.State(step = OTPLogin.Step.CODE, email = "alice@example.com"), time, OTPLogin.reducer(client, time.clock))
        for (attemptsLeft in listOf(2, 1, 0)) {
            store.send(OTPLogin.Action.CodeChanged("000000")).send(OTPLogin.Action.VerifyTapped)
            assertEquals(attemptsLeft, store.state.attemptsLeft)
            assertEquals("", store.state.code)
            assertEquals(AuthError.INVALID_CODE, store.state.error)
        }
        store.send(OTPLogin.Action.CodeChanged("123456"))
        assertFalse(store.state.canVerify)
    }

    @Test
    fun otherVerifyFailuresKeepTheAttempts() {
        for (error in listOf(AuthError.CODE_EXPIRED, AuthError.NETWORK)) {
            val time = TestTime()
            val client = AuthClient(verifyOTP = { _, _ -> fail(error) })
            val store = TestStore(OTPLogin.State(step = OTPLogin.Step.CODE, email = "a@b.co", code = "123456"), time, OTPLogin.reducer(client, time.clock))
            store.send(OTPLogin.Action.VerifyTapped)
            assertEquals(OTPLogin.State(step = OTPLogin.Step.CODE, email = "a@b.co", code = "123456", error = error), store.state)
        }
    }

    @Test
    fun agentPathsAndCommands() {
        val email = OTPLoginAgent.activeScreen(OTPLogin.State())
        assertEquals("auth/otp/email", email.path)
        assertEquals(listOf("email", "send"), email.commands.map { it.name })
        val code = OTPLoginAgent.activeScreen(OTPLogin.State(step = OTPLogin.Step.CODE, resendIn = 12))
        assertEquals("auth/otp/code", code.path)
        assertEquals(listOf("code", "verify", "resend"), code.commands.map { it.name })
        assertNull(code.command("resend")?.disabledReason)
        assertTrue(SummaryItem("resendIn", 12) in code.summary)
    }
}

class RegisterTest {
    private val valid = Register.State(name = "Carol", email = "carol@example.com", password = "Secret123", confirm = "Secret123", acceptedTerms = true)

    @Test
    fun validation() {
        assertTrue(valid.canSubmit)
        assertEquals(emptyList(), Register.State().issues)
        assertFalse(valid.copy(acceptedTerms = false).canSubmit)
        assertFalse(valid.copy(name = "  ").canSubmit)
        assertEquals(listOf(ValidationIssue.EMAIL), valid.copy(email = "carol").issues)
        assertEquals(listOf(ValidationIssue.CONFIRM_MISMATCH), valid.copy(confirm = "Secret124").issues)
        assertEquals(
            listOf(ValidationIssue.PASSWORD_TOO_SHORT, ValidationIssue.PASSWORD_MISSING_DIGIT, ValidationIssue.CONFIRM_MISMATCH),
            valid.copy(password = "abc").issues,
        )
        val split = valid.copy(email = "carol", password = "abc")
        assertEquals(listOf(ValidationIssue.EMAIL), split.emailFieldIssues)
        assertEquals(listOf(ValidationIssue.PASSWORD_TOO_SHORT, ValidationIssue.PASSWORD_MISSING_DIGIT), split.passwordFieldIssues)
        assertEquals(listOf(ValidationIssue.CONFIRM_MISMATCH), split.confirmFieldIssues)
    }

    /** Registering emails a code; the next step is email verification. */
    @Test
    fun happyPathGoesToVerification() {
        var registered: Triple<String, String, String>? = null
        val store = TestStore(valid, reducer = Register.reducer(AuthClient(register = { n, e, p -> registered = Triple(n, e, p) })))
        store.send(Register.Action.SubmitTapped)
        assertEquals(Triple("Carol", "carol@example.com", "Secret123"), registered)
        assertFalse(store.state.isLoading)
        assertEquals(Register.Action.Delegate.VerifyEmail("carol@example.com"), store.received.last())
    }

    @Test
    fun googleSignUpAndFailure() {
        val store = TestStore(Register.State(), reducer = Register.reducer(AuthClient(signInWithGoogle = { becca })))
        store.send(Register.Action.GoogleTapped)
        assertEquals(Register.Action.Delegate.Authenticated(becca), store.received.last())
        val failed = TestStore(Register.State(), reducer = Register.reducer(AuthClient(signInWithGoogle = { fail(AuthError.NETWORK) })))
        assertEquals(Register.State(error = AuthError.NETWORK), failed.send(Register.Action.GoogleTapped).state)
    }

    @Test
    fun failuresAreShownAndRevealTogglesKeepThem() {
        for (error in listOf(AuthError.EMAIL_TAKEN, AuthError.WEAK_PASSWORD, AuthError.NETWORK)) {
            val store = TestStore(valid, reducer = Register.reducer(AuthClient(register = { _, _, _ -> fail(error) })))
            store.send(Register.Action.SubmitTapped)
            assertEquals(valid.copy(error = error), store.state)
            store.send(Register.Action.ShowPasswordChanged(true)).send(Register.Action.ShowConfirmChanged(true))
            assertEquals(error, store.state.error)
            assertTrue(SummaryItem("revealed", "password,confirm") in RegisterAgent.activeScreen(store.state).summary)
        }
    }

    @Test
    fun agentCommands() {
        val screen = RegisterAgent.activeScreen(Register.State())
        assertEquals(
            listOf("name", "email", "password", "confirm", "show-password", "show-confirm", "terms", "submit", "google"),
            screen.commands.map { it.name },
        )
        assertEquals("canSubmit=false", screen.command("submit")?.disabledReason)
        assertTrue(SummaryItem("issues", "none") in screen.summary)
    }
}

class VerifyEmailTest {
    /** The countdown starts on appear, a resend during it is refused, a real resend restarts it, a verify cancels it. */
    @Test
    fun countdownResendAndVerify() {
        val time = TestTime()
        var resent = 0
        val client = AuthClient(resendVerification = { resent += 1 }, verifyEmail = { _, _ -> alice })
        val store = TestStore(VerifyEmail.State(email = "carol@example.com"), time, VerifyEmail.reducer(client, time.clock))
        store.send(VerifyEmail.Action.OnAppear)
        assertEquals(1, store.pending)
        store.send(VerifyEmail.Action.ResendTapped)
        assertEquals(AuthError.RESEND_NOT_AVAILABLE, store.state.error)
        store.advance(30.seconds)
        assertEquals(0, store.state.resendIn)
        assertEquals(0, store.pending)
        store.send(VerifyEmail.Action.ResendTapped)
        assertEquals(1, resent)
        assertEquals(30, store.state.resendIn)
        assertEquals(1, store.pending)
        store.send(VerifyEmail.Action.CodeChanged("123456")).send(VerifyEmail.Action.VerifyTapped)
        assertEquals(VerifyEmail.Action.Delegate.Authenticated(alice), store.received.last())
        assertEquals(0, store.pending)
    }

    /** Opened from login for an unverified account: nothing to count down, so resend works at once. */
    @Test
    fun noCountdownWhenResendIsAvailable() {
        val time = TestTime()
        val store = TestStore(VerifyEmail.State(email = "carol@example.com", resendIn = 0), time, VerifyEmail.reducer(AuthClient(resendVerification = {}), time.clock))
        store.send(VerifyEmail.Action.OnAppear)
        assertEquals(0, store.pending)
        store.send(VerifyEmail.Action.ResendTapped)
        assertEquals(30, store.state.resendIn)
        assertEquals(1, store.pending)
    }

    @Test
    fun failures() {
        for (error in listOf(AuthError.INVALID_CODE, AuthError.CODE_EXPIRED, AuthError.NETWORK)) {
            val time = TestTime()
            val client = AuthClient(verifyEmail = { _, _ -> fail(error) })
            val store = TestStore(VerifyEmail.State(email = "c@d.co", code = "123456", resendIn = 0), time, VerifyEmail.reducer(client, time.clock))
            store.send(VerifyEmail.Action.VerifyTapped)
            assertEquals(error, store.state.error)
            // Only a wrong code is cleared.
            assertEquals(if (error == AuthError.INVALID_CODE) "" else "123456", store.state.code)
        }
    }

    @Test
    fun codeIsDigitsOnlyAndAgentScreen() {
        val time = TestTime()
        val store = TestStore(VerifyEmail.State(email = "c@d.co"), time, VerifyEmail.reducer(AuthClient(), time.clock))
        assertEquals("123456", store.send(VerifyEmail.Action.CodeChanged("12a345678")).state.code)
        val screen = VerifyEmailAgent.activeScreen(VerifyEmail.State(email = "c@d.co"))
        assertEquals("auth/register/verify", screen.path)
        assertEquals(listOf("code", "verify", "resend"), screen.commands.map { it.name })
        assertEquals(VerifyEmail.Action.OnAppear, screen.appearAction)
    }
}

class ForgotPasswordTest {
    @Test
    fun requestAlwaysMovesToResetUnlessTheNetworkFails() {
        val store = TestStore(ForgotPassword.State(email = "nobody@example.com"), reducer = ForgotPassword.reducer(AuthClient(requestPasswordReset = {})))
        assertEquals(ForgotPassword.Step.RESET, store.send(ForgotPassword.Action.SendTapped).state.step)
        val failed = TestStore(ForgotPassword.State(email = "a@b.co"), reducer = ForgotPassword.reducer(AuthClient(requestPasswordReset = { fail(AuthError.NETWORK) })))
        assertEquals(ForgotPassword.State(email = "a@b.co", error = AuthError.NETWORK), failed.send(ForgotPassword.Action.SendTapped).state)
    }

    @Test
    fun resetThenBackToLogin() {
        var reset: Triple<String, String, String>? = null
        val start = ForgotPassword.State(step = ForgotPassword.Step.RESET, email = "alice@example.com", showPassword = true, showConfirm = true)
        val store = TestStore(start, reducer = ForgotPassword.reducer(AuthClient(resetPassword = { e, c, p -> reset = Triple(e, c, p) })))
        store.send(ForgotPassword.Action.CodeChanged("654321"))
            .send(ForgotPassword.Action.PasswordChanged("NewPass123"))
            .send(ForgotPassword.Action.ConfirmChanged("NewPass123"))
            .send(ForgotPassword.Action.SubmitTapped)
        assertEquals(Triple("alice@example.com", "654321", "NewPass123"), reset)
        // The form and the reveal toggles are reset once the password is.
        assertEquals(ForgotPassword.State(step = ForgotPassword.Step.DONE, email = "alice@example.com"), store.state)
        store.send(ForgotPassword.Action.BackToLoginTapped)
        assertEquals(ForgotPassword.Action.Delegate.PasswordReset("alice@example.com"), store.received.last())
    }

    @Test
    fun resetFailures() {
        for (error in listOf(AuthError.INVALID_CODE, AuthError.CODE_EXPIRED, AuthError.WEAK_PASSWORD, AuthError.NETWORK)) {
            val start = ForgotPassword.State(step = ForgotPassword.Step.RESET, email = "a@b.co", code = "654321", password = "NewPass123", confirm = "NewPass123")
            val store = TestStore(start, reducer = ForgotPassword.reducer(AuthClient(resetPassword = { _, _, _ -> fail(error) })))
            assertEquals(start.copy(error = error), store.send(ForgotPassword.Action.SubmitTapped).state)
        }
    }

    @Test
    fun validationAndAgentPaths() {
        val reset = ForgotPassword.State(step = ForgotPassword.Step.RESET, code = "654321", password = "abc", confirm = "abd")
        assertEquals(listOf(ValidationIssue.PASSWORD_TOO_SHORT, ValidationIssue.PASSWORD_MISSING_DIGIT), reset.passwordFieldIssues)
        assertEquals(listOf(ValidationIssue.CONFIRM_MISMATCH), reset.confirmFieldIssues)
        assertFalse(reset.canSubmit)
        assertTrue(reset.copy(password = "NewPass123", confirm = "NewPass123").canSubmit)
        assertEquals(listOf("email", "send"), ForgotPasswordAgent.activeScreen(ForgotPassword.State()).commands.map { it.name })
        assertEquals(
            listOf("code", "password", "confirm", "show-password", "show-confirm", "submit"),
            ForgotPasswordAgent.activeScreen(reset).commands.map { it.name },
        )
        val done = ForgotPasswordAgent.activeScreen(ForgotPassword.State(step = ForgotPassword.Step.DONE, email = "a@b.co"))
        assertEquals("auth/forgot/done", done.path)
        assertEquals(listOf(SummaryItem("email", "a@b.co")), done.summary)
    }
}

class AuthFlowTest {
    private fun store(state: AuthFlow.State = AuthFlow.State(), client: AuthClient = AuthClient(), time: TestTime = TestTime()) =
        TestStore(state, time, AuthFlow.reducer(client, time.clock))

    private fun screens(state: AuthFlow.State) = state.path.elements.map { it.screen }

    @Test
    fun loginLinksPushScreens() {
        val store = store(AuthFlow.State(login = Login.State(email = "alice@example.com")))
        store.send(AuthFlow.Action.Login(Login.Action.UseOTPTapped))
        store.send(AuthFlow.Action.Login(Login.Action.RegisterTapped))
        store.send(AuthFlow.Action.Login(Login.Action.ForgotPasswordTapped))
        assertEquals(
            listOf(
                AuthFlow.Path.OTPLogin(OTPLogin.State(email = "alice@example.com")),
                AuthFlow.Path.Register(Register.State()),
                AuthFlow.Path.ForgotPassword(ForgotPassword.State(email = "alice@example.com")),
            ),
            screens(store.state),
        )
        assertEquals(listOf(0, 1, 2), store.state.path.elements.map { it.id })
    }

    /** Every sign-in — from login, OTP, Google sign-up or email verification — carries Login's "Keep me signed in". */
    @Test
    fun authenticationIsForwardedWithKeepSignedIn() {
        val store = store(AuthFlow.State(login = Login.State(keepSignedIn = false)))
        store.send(AuthFlow.Action.Login(Login.Action.Delegate.Authenticated(alice)))
        assertEquals(AuthFlow.Action.Delegate.Authenticated(alice, remember = false), store.received.last())

        val pushed = store(AuthFlow.State(path = Stack<AuthFlow.Path>("auth").push(AuthFlow.Path.Register(Register.State()))))
        pushed.send(AuthFlow.Action.Element(0, AuthFlow.PathAction.Register(Register.Action.Delegate.Authenticated(becca))))
        assertEquals(AuthFlow.Action.Delegate.Authenticated(becca, remember = true), pushed.received.last())
    }

    @Test
    fun registrationPushesVerificationWithACooldownAndUnverifiedLoginWithout() {
        val store = store()
        store.send(AuthFlow.Action.Login(Login.Action.RegisterTapped))
        store.send(AuthFlow.Action.Element(0, AuthFlow.PathAction.Register(Register.Action.Delegate.VerifyEmail("carol@example.com"))))
        assertEquals(AuthFlow.Path.VerifyEmail(VerifyEmail.State(email = "carol@example.com")), screens(store.state).last())

        val unverified = store()
        unverified.send(AuthFlow.Action.Login(Login.Action.Delegate.VerifyEmail("carol@example.com")))
        assertEquals(listOf<AuthFlow.Path>(AuthFlow.Path.VerifyEmail(VerifyEmail.State(email = "carol@example.com", resendIn = 0))), screens(unverified.state))
    }

    /** The screens' own back buttons pop, like the agent's `back`. */
    @Test
    fun backButtonsPopTheStack() {
        val store = store()
        store.send(AuthFlow.Action.Login(Login.Action.RegisterTapped))
        store.send(AuthFlow.Action.Element(0, AuthFlow.PathAction.Register(Register.Action.BackTapped)))
        assertTrue(store.state.path.isEmpty())
        store.send(AuthFlow.Action.Login(Login.Action.UseOTPTapped))
        store.send(AuthFlow.Action.PopFrom(1))
        assertTrue(store.state.path.isEmpty())
    }

    @Test
    fun passwordResetPopsToLoginWithTheEmail() {
        val store = store(AuthFlow.State(login = Login.State(email = "old@example.com", password = "typed", error = AuthError.INVALID_CREDENTIALS)))
        store.send(AuthFlow.Action.Login(Login.Action.ForgotPasswordTapped))
        store.send(AuthFlow.Action.Element(0, AuthFlow.PathAction.ForgotPassword(ForgotPassword.Action.Delegate.PasswordReset("alice@example.com"))))
        assertTrue(store.state.path.isEmpty())
        assertEquals(Login.State(email = "alice@example.com"), store.state.login)
    }

    @Test
    fun poppingOTPCancelsItsCountdown() {
        val store = store(client = AuthClient(sendOTP = {}))
        store.send(AuthFlow.Action.Login(Login.Action.UseOTPTapped))
        store.send(AuthFlow.Action.Element(0, AuthFlow.PathAction.OTPLogin(OTPLogin.Action.EmailChanged("alice@example.com"))))
        store.send(AuthFlow.Action.Element(0, AuthFlow.PathAction.OTPLogin(OTPLogin.Action.SendTapped)))
        assertEquals(1, store.pending)
        store.send(AuthFlow.Action.PopFrom(0))
        assertEquals(0, store.pending)
        store.advance(30.seconds)
        assertEquals(0, store.received.count { it is AuthFlow.Action.Element && it.action == AuthFlow.PathAction.OTPLogin(OTPLogin.Action.Tick) })
    }

    @Test
    fun agentScreenLiftsPushedCommandsAndAddsBack() {
        val root = AuthFlowAgent.activeScreen(AuthFlow.State())
        assertEquals("auth/login", root.path)
        assertNull(root.command("back"))
        val pushed = AuthFlowAgent.activeScreen(AuthFlow.State(path = Stack<AuthFlow.Path>("auth").push(AuthFlow.Path.OTPLogin(OTPLogin.State()))))
        assertEquals("auth/otp/email", pushed.path)
        assertEquals(listOf("email", "send", "back"), pushed.commands.map { it.name })
        assertEquals("#0/auth/otp/email", pushed.identity)
        assertEquals(AuthFlow.Action.PopFrom(0), pushed.command("back")?.makeAction?.invoke(null))
    }

    @Test
    fun registryListsEveryAuthPath() {
        assertEquals(
            listOf(
                "auth/login", "auth/otp/email", "auth/otp/code", "auth/register", "auth/register/verify", "auth/forgot/email",
                "auth/forgot/reset", "auth/forgot/done",
            ),
            AuthFlowAgent.registry.map { it.path },
        )
        assertFalse(AuthFlowAgent.registry.first().commands.any { it.name == "back" })
        assertEquals("back", AuthFlowAgent.registry.last().commands.last().name)
    }
}
