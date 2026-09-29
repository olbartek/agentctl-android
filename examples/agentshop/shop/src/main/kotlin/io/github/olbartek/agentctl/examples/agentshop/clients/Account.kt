package io.github.olbartek.agentctl.examples.agentshop.clients

import io.github.olbartek.agentctl.MockMethod
import io.github.olbartek.agentctl.examples.agentshop.models.AccountError
import io.github.olbartek.agentctl.examples.agentshop.models.AccountException
import io.github.olbartek.agentctl.examples.agentshop.models.AccountProfile
import io.github.olbartek.agentctl.examples.agentshop.models.Address
import io.github.olbartek.agentctl.examples.agentshop.models.OnboardingAnswers
import io.github.olbartek.agentctl.examples.agentshop.models.ProductCategory
import io.github.olbartek.agentctl.examples.agentshop.models.ShopCalls
import io.github.olbartek.agentctl.examples.agentshop.models.codeOf

/**
 * The in-memory account server behind [AccountClient.live], keyed by the owner's email.
 *
 * The seeded accounts Alice, Bob and Locked are already onboarded, and Alice has a saved address. Every other account
 * — Nina (`nina@example.com`), anyone who registers, and the Google account — goes through onboarding once, and the
 * answers are kept here.
 */
class AccountBackend(seed: Map<String, AccountProfile> = MockProfiles.seed) {
    private val lock = Any()
    private val profiles = seed.toMutableMap()

    fun profile(email: String): AccountProfile = synchronized(lock) { profiles[email] ?: AccountProfile(needsOnboarding = true) }

    fun completeOnboarding(answers: OnboardingAnswers, email: String): AccountProfile = synchronized(lock) {
        val profile = AccountProfile(needsOnboarding = false, interests = answers.interests, address = answers.address)
        profiles[email] = profile
        profile
    }
}

object MockProfiles {
    val aliceAddress = Address(name = "Alice Liddell", street = "1 Rabbit Hole Ln", city = "Oxford", zip = "10001")

    val seed: Map<String, AccountProfile> = mapOf(
        "alice@example.com" to AccountProfile(false, listOf(ProductCategory.SHOES, ProductCategory.WATCHES), aliceAddress),
        "bob@example.com" to AccountProfile(needsOnboarding = false),
        "locked@example.com" to AccountProfile(needsOnboarding = false),
    )
}

/**
 * The signed-in shopper's account: whether they still need onboarding, their interests and saved address. Everything
 * is mocked: [live] talks to the in-memory [AccountBackend] and identifies the user from [SessionStorage].
 */
class AccountClient(
    val fetchProfile: suspend () -> AccountProfile = { unimplemented("account.fetchProfile") },
    val completeOnboarding: suspend (answers: OnboardingAnswers) -> AccountProfile = { unimplemented("account.completeOnboarding") },
) {
    companion object {
        fun live(calls: ShopCalls, storage: SessionStorage, backend: AccountBackend): AccountClient {
            suspend fun <T> call(name: String, body: (email: String) -> T): T =
                calls.call(name, { code -> AccountException(codeOf<AccountError>(code) ?: AccountError.NETWORK) }) {
                    val email = storage.currentSession?.user?.email ?: throw AccountException(AccountError.UNAUTHORIZED)
                    body(email)
                }
            return AccountClient(
                fetchProfile = { call("account.fetchProfile") { email -> backend.profile(email) } },
                completeOnboarding = { answers ->
                    call("account.completeOnboarding") { email -> backend.completeOnboarding(answers, email) }
                },
            )
        }

        /** Only `network`: a signed-out call is a bug, not a scenario. */
        val mockMethods: List<MockMethod> = listOf("fetchProfile", "completeOnboarding").map {
            MockMethod("account.$it", listOf(AccountError.NETWORK.code))
        }
    }
}
