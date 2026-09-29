package io.github.olbartek.agentctl.examples.agentshop.clients

import io.github.olbartek.agentctl.MockMethod
import io.github.olbartek.agentctl.examples.agentshop.models.AuthError
import io.github.olbartek.agentctl.examples.agentshop.models.AuthException
import io.github.olbartek.agentctl.examples.agentshop.models.Session
import io.github.olbartek.agentctl.examples.agentshop.models.ShopCalls
import io.github.olbartek.agentctl.examples.agentshop.models.codeOf

/**
 * Authentication endpoints. Everything is mocked: [live] talks to the in-memory [AuthBackend]. The constructor's
 * defaults fail, so a test states exactly the endpoints it expects to be called.
 */
class AuthClient(
    val login: suspend (email: String, password: String) -> Session = { _, _ -> unimplemented("auth.login") },
    val sendOTP: suspend (email: String) -> Unit = { unimplemented("auth.sendOTP") },
    val verifyOTP: suspend (email: String, code: String) -> Session = { _, _ -> unimplemented("auth.verifyOTP") },
    /** Creates an unverified account and emails a verification code (see [verifyEmail]). */
    val register: suspend (name: String, email: String, password: String) -> Unit = { _, _, _ -> unimplemented("auth.register") },
    val verifyEmail: suspend (email: String, code: String) -> Session = { _, _ -> unimplemented("auth.verifyEmail") },
    val resendVerification: suspend (email: String) -> Unit = { unimplemented("auth.resendVerification") },
    val signInWithGoogle: suspend () -> Session = { unimplemented("auth.signInWithGoogle") },
    /** Always succeeds, so the app never reveals which accounts exist. */
    val requestPasswordReset: suspend (email: String) -> Unit = { unimplemented("auth.requestPasswordReset") },
    val resetPassword: suspend (email: String, code: String, newPassword: String) -> Unit = { _, _, _ ->
        unimplemented("auth.resetPassword")
    },
) {
    companion object {
        /** The mocked client: every call goes through [ShopCalls.call] and then to `backend`. */
        fun live(calls: ShopCalls, backend: AuthBackend): AuthClient {
            suspend fun <T> call(name: String, body: () -> T): T =
                calls.call(name, { code -> AuthException(codeOf<AuthError>(code) ?: AuthError.NETWORK) }) { body() }
            return AuthClient(
                login = { email, password -> call("auth.login") { backend.login(email, password) } },
                sendOTP = { email -> call("auth.sendOTP") { backend.sendOTP(email) } },
                verifyOTP = { email, code -> call("auth.verifyOTP") { backend.verifyOTP(email, code) } },
                register = { name, email, password -> call("auth.register") { backend.register(name, email, password) } },
                verifyEmail = { email, code -> call("auth.verifyEmail") { backend.verifyEmail(email, code) } },
                resendVerification = { email -> call("auth.resendVerification") { backend.resendVerification(email) } },
                signInWithGoogle = { call("auth.signInWithGoogle") { backend.signInWithGoogle() } },
                requestPasswordReset = { email -> call("auth.requestPasswordReset") { backend.requestPasswordReset(email) } },
                resetPassword = { email, code, newPassword ->
                    call("auth.resetPassword") { backend.resetPassword(email, code, newPassword) }
                },
            )
        }

        /** The methods `mock auth.<method> <error>` accepts, with their error codes. */
        val mockMethods: List<MockMethod> = listOf(
            "login", "sendOTP", "verifyOTP", "register", "verifyEmail", "resendVerification", "signInWithGoogle",
            "requestPasswordReset", "resetPassword",
        ).map { MockMethod("auth.$it", AuthError.entries.map { error -> error.code }) }
    }
}

/** What an endpoint a test did not provide does when it is called anyway. */
internal fun unimplemented(name: String): Nothing = throw UnsupportedOperationException("$name is not implemented in this test")
