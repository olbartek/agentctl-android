package io.github.olbartek.agentctl.examples.agentshop.clients

import io.github.olbartek.agentctl.examples.agentshop.models.Session
import io.github.olbartek.agentctl.examples.agentshop.models.User

/** A seeded account in the mock auth backend. */
data class MockAccount(
    val user: User,
    /** `null` for accounts that can never sign in with a password. */
    val password: String?,
    val alwaysLocked: Boolean = false,
    /** Registered accounts start unverified until the emailed code is entered. */
    val isVerified: Boolean = true,
)

/** The test accounts the README lists. */
object MockAccounts {
    val alice = MockAccount(User("u1", "Alice", "alice@example.com"), password = "Passw0rd!")
    val bob = MockAccount(User("u2", "Bob", "bob@example.com"), password = "Hunter22x")
    val locked = MockAccount(User("u3", "Locked", "locked@example.com"), password = null, alwaysLocked = true)

    /** A seeded account that has never been through onboarding, so signing in starts it. */
    val nina = MockAccount(User("u4", "Nina", "nina@example.com"), password = "Passw0rd!")

    /** The account "Sign in with Google" returns (the design's persona). It has no password. */
    val google = MockAccount(User("u-google", "Becca Ade", "becca@gmail.com"), password = null)

    val seed: List<MockAccount> = listOf(alice, bob, locked, nina, google)

    /** The one-time login code. Always the same in the mock backend. */
    const val OTP_CODE: String = "123456"

    /** The code emailed after registration. Always the same in the mock backend. */
    const val VERIFICATION_CODE: String = "123456"

    /** The password-reset code. Always the same in the mock backend. */
    const val RESET_CODE: String = "654321"

    fun session(user: User): Session = Session(user, "mock-token-${user.id}")

    /** The session `login-as <name>` saves, for `alice` and `bob`. */
    fun session(named: String): Session? = when (named) {
        "alice" -> session(alice.user)
        "bob" -> session(bob.user)
        else -> null
    }
}
