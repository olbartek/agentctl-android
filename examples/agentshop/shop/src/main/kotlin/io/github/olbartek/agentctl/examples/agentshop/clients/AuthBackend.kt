package io.github.olbartek.agentctl.examples.agentshop.clients

import io.github.olbartek.agentctl.examples.agentshop.models.AuthError
import io.github.olbartek.agentctl.examples.agentshop.models.AuthException
import io.github.olbartek.agentctl.examples.agentshop.models.Session
import io.github.olbartek.agentctl.examples.agentshop.models.User
import io.github.olbartek.agentctl.examples.agentshop.models.isStrongPassword
import io.github.olbartek.agentctl.examples.agentshop.models.trimmingWhitespaces
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The in-memory auth server behind [AuthClient.live].
 *
 * - Login: an unknown email or wrong password gives `invalidCredentials`. The 5th consecutive failure on an account
 *   gives `accountLocked`, and the account stays locked until a password reset. `locked@example.com` is always locked.
 * - OTP: `sendOTP` for an unknown email gives `unknownEmail`; a second send within 30 s gives `resendNotAvailable`.
 *   A code expires 5 minutes after sending (`codeExpired`). After 3 wrong codes every verify fails with
 *   `invalidCode` until a resend.
 * - Register: `emailTaken` for an existing account, `weakPassword` below the password rules. A new account is
 *   unverified: a code is emailed, and until `verifyEmail` succeeds, logging in gives `emailNotVerified`. Resending
 *   the code is blocked for 30 s; a code expires after 5 minutes.
 * - Google: `signInWithGoogle` always signs in as the Google test account (Becca Ade).
 * - Password reset: requesting always succeeds but only issues a code for known accounts. A wrong code gives
 *   `invalidCode`, an old one `codeExpired`, a weak password `weakPassword`. A reset unlocks the account.
 *
 * Times are measured on [uptime], which `advance` moves headlessly, so `advance 5m` expires codes.
 *
 * @param uptime the time elapsed since some fixed origin: the virtual clock headlessly, a monotonic clock in the app.
 */
class AuthBackend(private val uptime: () -> Duration, accounts: List<MockAccount> = MockAccounts.seed) {
    private class IssuedCode(val code: String, val issuedAt: Duration, var failedAttempts: Int = 0)

    private val lock = Any()
    private val accounts = LinkedHashMap<String, MockAccount>().apply { accounts.forEach { putIfAbsent(it.user.email, it) } }
    private val failedLogins = mutableMapOf<String, Int>()
    private val lockedEmails = mutableSetOf<String>()
    private val otpCodes = mutableMapOf<String, IssuedCode>()
    private val resetCodes = mutableMapOf<String, IssuedCode>()
    private val verificationCodes = mutableMapOf<String, IssuedCode>()
    private var nextUserNumber = accounts.size + 1

    fun login(email: String, password: String): Session = synchronized(lock) {
        val key = normalized(email)
        val account = accounts[key] ?: fail(AuthError.INVALID_CREDENTIALS)
        if (account.alwaysLocked || key in lockedEmails) fail(AuthError.ACCOUNT_LOCKED)
        if (account.password == null || account.password != password) {
            val failures = (failedLogins[key] ?: 0) + 1
            failedLogins[key] = failures
            if (failures >= LOCKOUT_THRESHOLD) {
                lockedEmails.add(key)
                fail(AuthError.ACCOUNT_LOCKED)
            }
            fail(AuthError.INVALID_CREDENTIALS)
        }
        failedLogins.remove(key)
        if (!account.isVerified) fail(AuthError.EMAIL_NOT_VERIFIED)
        MockAccounts.session(account.user)
    }

    fun sendOTP(email: String): Unit = synchronized(lock) {
        val key = normalized(email)
        val account = accounts[key] ?: fail(AuthError.UNKNOWN_EMAIL)
        if (!account.isVerified) fail(AuthError.EMAIL_NOT_VERIFIED)
        val issued = otpCodes[key]
        if (issued != null && elapsed(issued) < RESEND_COOLDOWN) fail(AuthError.RESEND_NOT_AVAILABLE)
        otpCodes[key] = issue(MockAccounts.OTP_CODE)
    }

    fun verifyOTP(email: String, code: String): Session = synchronized(lock) {
        val key = normalized(email)
        val issued = otpCodes[key] ?: fail(AuthError.INVALID_CODE)
        val account = accounts[key] ?: fail(AuthError.INVALID_CODE)
        if (elapsed(issued) >= CODE_LIFETIME) fail(AuthError.CODE_EXPIRED)
        if (issued.failedAttempts >= MAX_CODE_ATTEMPTS || code != issued.code) {
            issued.failedAttempts += 1
            fail(AuthError.INVALID_CODE)
        }
        otpCodes.remove(key)
        if (account.alwaysLocked || key in lockedEmails) fail(AuthError.ACCOUNT_LOCKED)
        MockAccounts.session(account.user)
    }

    /** Creates an unverified account and emails a verification code. */
    fun register(name: String, email: String, password: String): Unit = synchronized(lock) {
        val key = normalized(email)
        if (accounts[key] != null) fail(AuthError.EMAIL_TAKEN)
        if (!isStrongPassword(password)) fail(AuthError.WEAK_PASSWORD)
        val user = User("u$nextUserNumber", name, key)
        nextUserNumber += 1
        accounts[key] = MockAccount(user, password, isVerified = false)
        verificationCodes[key] = issue(MockAccounts.VERIFICATION_CODE)
    }

    /** Checks the emailed code, marks the account verified and signs in. */
    fun verifyEmail(email: String, code: String): Session = synchronized(lock) {
        val key = normalized(email)
        val issued = verificationCodes[key] ?: fail(AuthError.INVALID_CODE)
        val account = accounts[key] ?: fail(AuthError.INVALID_CODE)
        if (elapsed(issued) >= CODE_LIFETIME) fail(AuthError.CODE_EXPIRED)
        if (code != issued.code) fail(AuthError.INVALID_CODE)
        verificationCodes.remove(key)
        accounts[key] = account.copy(isVerified = true)
        MockAccounts.session(account.user)
    }

    /** Emails a new verification code; blocked for 30 s after the previous one. */
    fun resendVerification(email: String): Unit = synchronized(lock) {
        val key = normalized(email)
        val account = accounts[key]
        if (account == null || account.isVerified) fail(AuthError.INVALID_CODE)
        val issued = verificationCodes[key]
        if (issued != null && elapsed(issued) < RESEND_COOLDOWN) fail(AuthError.RESEND_NOT_AVAILABLE)
        verificationCodes[key] = issue(MockAccounts.VERIFICATION_CODE)
    }

    /** "Continue with Google": always the Google test account, created (verified) if needed. */
    fun signInWithGoogle(): Session = synchronized(lock) {
        val google = MockAccounts.google
        accounts.putIfAbsent(google.user.email, google)
        MockAccounts.session(google.user)
    }

    fun requestPasswordReset(email: String): Unit = synchronized(lock) {
        val key = normalized(email)
        if (accounts[key] == null) return
        resetCodes[key] = issue(MockAccounts.RESET_CODE)
    }

    fun resetPassword(email: String, code: String, newPassword: String): Unit = synchronized(lock) {
        val key = normalized(email)
        val issued = resetCodes[key] ?: fail(AuthError.INVALID_CODE)
        val account = accounts[key] ?: fail(AuthError.INVALID_CODE)
        if (elapsed(issued) >= CODE_LIFETIME) fail(AuthError.CODE_EXPIRED)
        if (code != issued.code) fail(AuthError.INVALID_CODE)
        if (!isStrongPassword(newPassword)) fail(AuthError.WEAK_PASSWORD)
        accounts[key] = account.copy(password = newPassword)
        resetCodes.remove(key)
        lockedEmails.remove(key)
        failedLogins.remove(key)
    }

    private fun issue(code: String) = IssuedCode(code, uptime())

    private fun elapsed(issued: IssuedCode): Duration = uptime() - issued.issuedAt

    private fun normalized(email: String): String = email.trimmingWhitespaces().lowercase()

    private fun fail(error: AuthError): Nothing = throw AuthException(error)

    companion object {
        const val LOCKOUT_THRESHOLD: Int = 5
        const val MAX_CODE_ATTEMPTS: Int = 3
        val RESEND_COOLDOWN: Duration = 30.seconds
        val CODE_LIFETIME: Duration = 5.minutes
    }
}
