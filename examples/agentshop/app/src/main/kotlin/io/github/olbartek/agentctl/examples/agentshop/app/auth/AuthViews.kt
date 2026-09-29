package io.github.olbartek.agentctl.examples.agentshop.app.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.olbartek.agentctl.examples.agentshop.app.design.ASSecureField
import io.github.olbartek.agentctl.examples.agentshop.app.design.ASTextField
import io.github.olbartek.agentctl.examples.agentshop.app.design.Checkbox
import io.github.olbartek.agentctl.examples.agentshop.app.design.CodeInputField
import io.github.olbartek.agentctl.examples.agentshop.app.design.DividerLabel
import io.github.olbartek.agentctl.examples.agentshop.app.design.FieldKind
import io.github.olbartek.agentctl.examples.agentshop.app.design.FormField
import io.github.olbartek.agentctl.examples.agentshop.app.design.FormScreen
import io.github.olbartek.agentctl.examples.agentshop.app.design.GoogleButton
import io.github.olbartek.agentctl.examples.agentshop.app.design.HeaderStyle
import io.github.olbartek.agentctl.examples.agentshop.app.design.InlineError
import io.github.olbartek.agentctl.examples.agentshop.app.design.LinkButton
import io.github.olbartek.agentctl.examples.agentshop.app.design.Metrics
import io.github.olbartek.agentctl.examples.agentshop.app.design.Palette
import io.github.olbartek.agentctl.examples.agentshop.app.design.PrimaryButton
import io.github.olbartek.agentctl.examples.agentshop.app.design.PromptLink
import io.github.olbartek.agentctl.examples.agentshop.app.design.ScreenHeader
import io.github.olbartek.agentctl.examples.agentshop.app.design.SecureFieldKind
import io.github.olbartek.agentctl.examples.agentshop.app.design.Typography
import io.github.olbartek.agentctl.examples.agentshop.app.design.message
import io.github.olbartek.agentctl.examples.agentshop.app.design.screenTag
import io.github.olbartek.agentctl.examples.agentshop.auth.AuthFlow
import io.github.olbartek.agentctl.examples.agentshop.auth.ForgotPassword
import io.github.olbartek.agentctl.examples.agentshop.auth.ForgotPasswordAgent
import io.github.olbartek.agentctl.examples.agentshop.auth.Login
import io.github.olbartek.agentctl.examples.agentshop.auth.LoginAgent
import io.github.olbartek.agentctl.examples.agentshop.auth.OTPLogin
import io.github.olbartek.agentctl.examples.agentshop.auth.OTPLoginAgent
import io.github.olbartek.agentctl.examples.agentshop.auth.Register
import io.github.olbartek.agentctl.examples.agentshop.auth.RegisterAgent
import io.github.olbartek.agentctl.examples.agentshop.auth.VerifyEmail
import io.github.olbartek.agentctl.examples.agentshop.auth.VerifyEmailAgent

/**
 * The auth stack: Login at the root, the top pushed screen over it. The system back gesture does what the screen's
 * own back button does.
 */
@Composable
fun AuthFlowView(state: AuthFlow.State, send: (AuthFlow.Action) -> Unit) {
    val top = state.path.top
    if (top == null) {
        LoginView(state.login) { send(AuthFlow.Action.Login(it)) }
        return
    }
    // A new push is a new screen: its `onAppear` runs again, as in a navigation stack.
    key(top.id) {
        val id = top.id
        BackHandler { send(AuthFlow.Action.PopFrom(id)) }
        when (val screen = top.screen) {
            is AuthFlow.Path.OTPLogin -> OTPLoginView(screen.state) { send(AuthFlow.Action.Element(id, AuthFlow.PathAction.OTPLogin(it))) }
            is AuthFlow.Path.Register -> RegisterView(screen.state) { send(AuthFlow.Action.Element(id, AuthFlow.PathAction.Register(it))) }
            is AuthFlow.Path.VerifyEmail ->
                VerifyEmailView(screen.state) { send(AuthFlow.Action.Element(id, AuthFlow.PathAction.VerifyEmail(it))) }
            is AuthFlow.Path.ForgotPassword ->
                ForgotPasswordView(screen.state) { send(AuthFlow.Action.Element(id, AuthFlow.PathAction.ForgotPassword(it))) }
        }
    }
}

/** The Figma "Log in" frame. Login is the root of the auth stack, so it has no back button. */
@Composable
fun LoginView(state: Login.State, send: (Login.Action) -> Unit) {
    FormScreen(Modifier.screenTag(LoginAgent.screenPath(state))) {
        ScreenHeader("Login")

        GoogleButton("Sign in with Google", isLoading = state.isGoogleLoading, testTag = "Login.google", modifier = Modifier.padding(top = 72.dp)) {
            send(Login.Action.GoogleTapped)
        }

        DividerLabel("or sign in with", Modifier.padding(top = 24.dp))

        Column(Modifier.padding(top = Metrics.sectionSpacing), verticalArrangement = Arrangement.spacedBy(Metrics.fieldSpacing)) {
            FormField("Email Address") {
                ASTextField("name@example.com", state.email, { send(Login.Action.EmailChanged(it)) }, kind = FieldKind.EMAIL, testTag = "Login.email")
            }
            FormField(
                "Password",
                error = state.error?.message,
                errorCode = state.error?.code,
                accessory = { LinkButton("Forgot Password", testTag = "Login.forgot") { send(Login.Action.ForgotPasswordTapped) } },
            ) {
                ASSecureField(
                    text = state.password,
                    onChange = { send(Login.Action.PasswordChanged(it)) },
                    isRevealed = state.showPassword,
                    onRevealChange = { send(Login.Action.ShowPasswordChanged(it)) },
                    testTag = "Login.password",
                    revealTag = "Login.show-password",
                )
            }
        }

        Checkbox(
            isOn = state.keepSignedIn,
            onChange = { send(Login.Action.KeepSignedInChanged(it)) },
            testTag = "Login.keep-signed-in",
            modifier = Modifier.padding(top = 32.dp),
        ) {
            Text("Keep me signed in", style = Typography.body.copy(color = Palette.textBlack))
        }

        PrimaryButton(
            "Login",
            isLoading = state.isLoading,
            isEnabled = state.canSubmit,
            testTag = "Login.submit",
            modifier = Modifier.padding(top = 16.dp),
        ) { send(Login.Action.SubmitTapped) }

        LinkButton(
            "Log in with a one-time code",
            testTag = "Login.use-otp",
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 24.dp),
        ) { send(Login.Action.UseOTPTapped) }

        PromptLink(
            "Don’t have an Account?",
            link = "Sign up here",
            testTag = "Login.register",
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 32.dp),
        ) { send(Login.Action.RegisterTapped) }
    }
}

/** Login with an emailed one-time code, in the style of the Figma "Verify Email" frame. */
@Composable
fun OTPLoginView(state: OTPLogin.State, send: (OTPLogin.Action) -> Unit) {
    FormScreen(Modifier.screenTag(OTPLoginAgent.screenPath(state))) {
        when (state.step) {
            OTPLogin.Step.EMAIL -> {
                ScreenHeader(
                    "Login with a Code",
                    HeaderStyle.Leading("Enter the email address of your account. We’ll email you a 6-digit code to log in."),
                ) { send(OTPLogin.Action.BackTapped) }
                FormField("Email Address", Modifier.padding(top = 64.dp), error = state.error?.message, errorCode = state.error?.code) {
                    ASTextField("name@example.com", state.email, { send(OTPLogin.Action.EmailChanged(it)) }, kind = FieldKind.EMAIL, testTag = "OTPLogin.email")
                }
                PrimaryButton(
                    "Send Code",
                    isLoading = state.isLoading,
                    isEnabled = state.canSend,
                    testTag = "OTPLogin.send",
                    modifier = Modifier.padding(top = Metrics.sectionSpacing),
                ) { send(OTPLogin.Action.SendTapped) }
                PromptLink(
                    "Prefer a password?",
                    link = "Login with password",
                    promptColor = Palette.textSecondary,
                    linkStyle = Typography.body,
                    testTag = "OTPLogin.password-login",
                    modifier = Modifier.padding(top = Metrics.sectionSpacing),
                ) { send(OTPLogin.Action.BackTapped) }
            }

            OTPLogin.Step.CODE -> {
                ScreenHeader(
                    "Check your email",
                    HeaderStyle.Leading("We’ve sent a 6-digit code to ${state.email}. It expires in 5 minutes."),
                ) { send(OTPLogin.Action.BackTapped) }
                Column(Modifier.padding(top = 64.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Enter Code", style = Typography.sectionLabel.copy(color = Palette.textSubtle))
                    CodeInputField(state.code, { send(OTPLogin.Action.CodeChanged(it)) }, testTag = "OTPLogin.code")
                    state.error?.let { InlineError(it.message, code = it.code) }
                    if (state.attemptsLeft == 0) InlineError("Too many wrong codes. Request a new one.")
                }
                PrimaryButton(
                    "Login",
                    isLoading = state.isLoading,
                    isEnabled = state.canVerify,
                    testTag = "OTPLogin.verify",
                    modifier = Modifier.padding(top = Metrics.sectionSpacing),
                ) { send(OTPLogin.Action.VerifyTapped) }
                PromptLink(
                    "Didn’t see your email?",
                    link = if (state.resendIn > 0) "Resend in ${state.resendIn}s" else "Resend",
                    promptColor = Palette.textSecondary,
                    linkStyle = Typography.bodyMedium,
                    underlined = true,
                    isEnabled = state.resendIn == 0 && !state.isLoading,
                    testTag = "OTPLogin.resend",
                    modifier = Modifier.padding(top = Metrics.sectionSpacing),
                ) { send(OTPLogin.Action.ResendTapped) }
            }
        }
    }
}

/** The Figma "Signup" frame. */
@Composable
fun RegisterView(state: Register.State, send: (Register.Action) -> Unit) {
    FormScreen(Modifier.screenTag(RegisterAgent.screenPath(state))) {
        ScreenHeader("Signup") { send(Register.Action.BackTapped) }

        GoogleButton(
            "Sign up with Google",
            isLoading = state.isGoogleLoading,
            testTag = "Register.google",
            modifier = Modifier.padding(top = Metrics.sectionSpacing),
        ) { send(Register.Action.GoogleTapped) }

        DividerLabel("or sign up with", Modifier.padding(top = 24.dp))

        Column(Modifier.padding(top = Metrics.sectionSpacing), verticalArrangement = Arrangement.spacedBy(Metrics.fieldSpacing)) {
            FormField("Full Name") {
                ASTextField("Your name", state.name, { send(Register.Action.NameChanged(it)) }, kind = FieldKind.NAME, testTag = "Register.name")
            }
            FormField("Email Address", error = state.emailFieldIssues.message) {
                ASTextField("name@example.com", state.email, { send(Register.Action.EmailChanged(it)) }, kind = FieldKind.EMAIL, testTag = "Register.email")
            }
            FormField("Password", error = state.passwordFieldIssues.message) {
                ASSecureField(
                    text = state.password,
                    onChange = { send(Register.Action.PasswordChanged(it)) },
                    isRevealed = state.showPassword,
                    onRevealChange = { send(Register.Action.ShowPasswordChanged(it)) },
                    kind = SecureFieldKind.NEW_PASSWORD,
                    testTag = "Register.password",
                    revealTag = "Register.show-password",
                )
            }
            FormField("Confirm Password", error = state.confirmFieldIssues.message) {
                ASSecureField(
                    text = state.confirm,
                    onChange = { send(Register.Action.ConfirmChanged(it)) },
                    isRevealed = state.showConfirm,
                    onRevealChange = { send(Register.Action.ShowConfirmChanged(it)) },
                    kind = SecureFieldKind.NEW_PASSWORD,
                    testTag = "Register.confirm",
                    revealTag = "Register.show-confirm",
                )
            }
        }

        Checkbox(
            isOn = state.acceptedTerms,
            onChange = { send(Register.Action.TermsChanged(it)) },
            spacing = 16.dp,
            testTag = "Register.terms",
            modifier = Modifier.padding(top = 32.dp),
        ) {
            Text(
                "By creating an account, I accept AgentShop’s Terms of Use and Privacy Policy",
                style = Typography.body.copy(color = Palette.textSecondary),
            )
        }

        state.error?.let { InlineError(it.message, code = it.code, modifier = Modifier.padding(top = 16.dp)) }

        PrimaryButton(
            "Signup",
            isLoading = state.isLoading,
            isEnabled = state.canSubmit,
            testTag = "Register.submit",
            modifier = Modifier.padding(top = 16.dp),
        ) { send(Register.Action.SubmitTapped) }

        PromptLink(
            "Have an Account?",
            link = "Sign in here",
            testTag = "Register.sign-in",
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 32.dp),
        ) { send(Register.Action.BackTapped) }
    }
}

/** The Figma "Verify Email" frame. */
@Composable
fun VerifyEmailView(state: VerifyEmail.State, send: (VerifyEmail.Action) -> Unit) {
    LaunchedEffect(Unit) { send(VerifyEmail.Action.OnAppear) }
    FormScreen(Modifier.screenTag(VerifyEmailAgent.screenPath(state))) {
        ScreenHeader(
            "Please verify your email address",
            HeaderStyle.Leading("We’ve sent an email to ${state.email}, please enter the code below."),
        ) { send(VerifyEmail.Action.BackTapped) }

        Column(Modifier.padding(top = 64.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Enter Code", style = Typography.sectionLabel.copy(color = Palette.textSubtle))
            CodeInputField(state.code, { send(VerifyEmail.Action.CodeChanged(it)) }, testTag = "VerifyEmail.code")
            state.error?.let { InlineError(it.message, code = it.code) }
        }

        PrimaryButton(
            "Create Account",
            isLoading = state.isLoading,
            isEnabled = state.canVerify,
            testTag = "VerifyEmail.verify",
            modifier = Modifier.padding(top = Metrics.sectionSpacing),
        ) { send(VerifyEmail.Action.VerifyTapped) }

        PromptLink(
            "Didn’t see your email?",
            link = if (state.resendIn > 0) "Resend in ${state.resendIn}s" else "Resend",
            promptColor = Palette.textSecondary,
            linkStyle = Typography.bodyMedium,
            underlined = true,
            isEnabled = state.resendIn == 0 && !state.isLoading,
            testTag = "VerifyEmail.resend",
            modifier = Modifier.padding(top = Metrics.sectionSpacing),
        ) { send(VerifyEmail.Action.ResendTapped) }
    }
}

/** The Figma "Forgot Password" frame (the email step), and the reset and done steps in the same style. */
@Composable
fun ForgotPasswordView(state: ForgotPassword.State, send: (ForgotPassword.Action) -> Unit) {
    FormScreen(Modifier.screenTag(ForgotPasswordAgent.screenPath(state))) {
        when (state.step) {
            ForgotPassword.Step.EMAIL -> {
                ScreenHeader(
                    "Forgot Password",
                    HeaderStyle.Leading(
                        "Enter the email address registered with your account. We’ll send you a code to reset your password.",
                    ),
                ) { send(ForgotPassword.Action.BackTapped) }
                FormField("Email Address", Modifier.padding(top = 64.dp), error = state.error?.message, errorCode = state.error?.code) {
                    ASTextField(
                        "name@example.com",
                        state.email,
                        { send(ForgotPassword.Action.EmailChanged(it)) },
                        kind = FieldKind.EMAIL,
                        testTag = "ForgotPassword.email",
                    )
                }
                PrimaryButton(
                    "Submit",
                    isLoading = state.isLoading,
                    isEnabled = state.canSend,
                    testTag = "ForgotPassword.send",
                    modifier = Modifier.padding(top = Metrics.sectionSpacing),
                ) { send(ForgotPassword.Action.SendTapped) }
                LoginLink(send)
            }

            ForgotPassword.Step.RESET -> {
                ScreenHeader(
                    "Reset Password",
                    HeaderStyle.Leading("If ${state.email} has an account, we’ve sent it a 6-digit code. Enter it with your new password."),
                ) { send(ForgotPassword.Action.BackTapped) }
                Column(Modifier.padding(top = 64.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Enter Code", style = Typography.sectionLabel.copy(color = Palette.textSubtle))
                    CodeInputField(state.code, { send(ForgotPassword.Action.CodeChanged(it)) }, testTag = "ForgotPassword.code")
                }
                Column(Modifier.padding(top = Metrics.fieldSpacing), verticalArrangement = Arrangement.spacedBy(Metrics.fieldSpacing)) {
                    FormField("New Password", error = state.passwordFieldIssues.message) {
                        ASSecureField(
                            text = state.password,
                            onChange = { send(ForgotPassword.Action.PasswordChanged(it)) },
                            isRevealed = state.showPassword,
                            onRevealChange = { send(ForgotPassword.Action.ShowPasswordChanged(it)) },
                            kind = SecureFieldKind.NEW_PASSWORD,
                            testTag = "ForgotPassword.password",
                            revealTag = "ForgotPassword.show-password",
                        )
                    }
                    FormField("Confirm Password", error = state.confirmFieldIssues.message) {
                        ASSecureField(
                            text = state.confirm,
                            onChange = { send(ForgotPassword.Action.ConfirmChanged(it)) },
                            isRevealed = state.showConfirm,
                            onRevealChange = { send(ForgotPassword.Action.ShowConfirmChanged(it)) },
                            kind = SecureFieldKind.NEW_PASSWORD,
                            testTag = "ForgotPassword.confirm",
                            revealTag = "ForgotPassword.show-confirm",
                        )
                    }
                }
                state.error?.let { InlineError(it.message, code = it.code, modifier = Modifier.padding(top = 16.dp)) }
                PrimaryButton(
                    "Reset Password",
                    isLoading = state.isLoading,
                    isEnabled = state.canSubmit,
                    testTag = "ForgotPassword.submit",
                    modifier = Modifier.padding(top = Metrics.sectionSpacing),
                ) { send(ForgotPassword.Action.SubmitTapped) }
                LoginLink(send)
            }

            ForgotPassword.Step.DONE -> {
                ScreenHeader(
                    "Password Changed",
                    HeaderStyle.Leading("Your password has been reset. Log in with your new password."),
                ) { send(ForgotPassword.Action.BackTapped) }
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Palette.checkboxOn,
                    modifier = Modifier.padding(top = 64.dp).size(64.dp).align(Alignment.CenterHorizontally),
                )
                PrimaryButton("Back to Login", testTag = "ForgotPassword.to-login", modifier = Modifier.padding(top = 64.dp)) {
                    send(ForgotPassword.Action.BackToLoginTapped)
                }
            }
        }
    }
}

@Composable
private fun LoginLink(send: (ForgotPassword.Action) -> Unit) {
    PromptLink(
        "Remembered password?",
        link = "Login to your account",
        promptColor = Palette.textSecondary,
        linkStyle = Typography.body,
        testTag = "ForgotPassword.login",
        modifier = Modifier.fillMaxWidth().padding(top = Metrics.sectionSpacing),
    ) { send(ForgotPassword.Action.BackTapped) }
}
