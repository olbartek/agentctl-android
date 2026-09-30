package io.github.olbartek.agentctl.examples.agentshop.app

import io.github.olbartek.agentctl.AgentCommandError
import io.github.olbartek.agentctl.AgentCommandException
import io.github.olbartek.agentctl.IncrementingUuids
import io.github.olbartek.agentctl.examples.agentshop.TestStore
import io.github.olbartek.agentctl.examples.agentshop.TestTime
import io.github.olbartek.agentctl.examples.agentshop.auth.AuthFlow
import io.github.olbartek.agentctl.examples.agentshop.auth.Login
import io.github.olbartek.agentctl.examples.agentshop.auth.OTPLogin
import io.github.olbartek.agentctl.examples.agentshop.auth.Register
import io.github.olbartek.agentctl.examples.agentshop.clients.AccountClient
import io.github.olbartek.agentctl.examples.agentshop.clients.AuthClient
import io.github.olbartek.agentctl.examples.agentshop.clients.MockAccounts
import io.github.olbartek.agentctl.examples.agentshop.clients.SessionClient
import io.github.olbartek.agentctl.examples.agentshop.clients.StoredSession
import io.github.olbartek.agentctl.examples.agentshop.home.HomeTabs
import io.github.olbartek.agentctl.examples.agentshop.home.HomeTabsAgent
import io.github.olbartek.agentctl.examples.agentshop.models.AccountError
import io.github.olbartek.agentctl.examples.agentshop.models.AccountException
import io.github.olbartek.agentctl.examples.agentshop.models.AccountProfile
import io.github.olbartek.agentctl.examples.agentshop.navigation.Stack
import io.github.olbartek.agentctl.examples.agentshop.onboarding.InterestsAgent
import io.github.olbartek.agentctl.examples.agentshop.onboarding.OnboardingFlow
import io.github.olbartek.agentctl.examples.agentshop.shop.CheckoutAgent
import io.github.olbartek.agentctl.examples.agentshop.shop.ShopFeedAgent
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppFeatureTest {
    private val alice = MockAccounts.session(named = "alice")!!
    private val firstHome = UUID(0, 0)

    private fun store(
        state: AppFeature.State = AppFeature.State.Launching,
        sessionClient: SessionClient = SessionClient(),
        accountClient: AccountClient = AccountClient(),
        authClient: AuthClient = AuthClient(),
        time: TestTime = TestTime(),
    ) = TestStore(
        state,
        time,
        AppFeature.reducer(
            ShopDependencies(
                clock = time.clock,
                uuids = IncrementingUuids(),
                authClient = authClient,
                sessionClient = sessionClient,
                accountClient = accountClient,
            ),
        ),
    )

    @Test
    fun launchWithoutASessionShowsAuth() {
        val store = store(sessionClient = SessionClient(current = { null })).send(AppFeature.Action.Appeared)
        assertEquals(AppFeature.State.Auth(AuthFlow.State()), store.state)
    }

    @Test
    fun launchWithASessionRestoresHome() {
        val store = store(sessionClient = SessionClient(current = { alice })).send(AppFeature.Action.Appeared)
        assertEquals(AppFeature.State.Home(HomeTabs.State(firstHome, alice)), store.state)
    }

    @Test
    fun appearingAgainAfterLaunchDoesNothing() {
        val store = store(AppFeature.State.Auth(AuthFlow.State())).send(AppFeature.Action.Appeared)
        assertEquals(listOf<AppFeature.Action>(AppFeature.Action.Appeared), store.received)
    }

    @Test
    fun authenticationSavesTheSessionThenGoesHome() {
        for (remember in listOf(true, false)) {
            var saved: StoredSession? = null
            val store = store(
                AppFeature.State.Auth(AuthFlow.State()),
                sessionClient = SessionClient(save = { session, r -> saved = StoredSession(session, r) }),
                accountClient = AccountClient(fetchProfile = { AccountProfile(needsOnboarding = false) }),
            )
            store.send(AppFeature.Action.Auth(AuthFlow.Action.Delegate.Authenticated(alice, remember)))
            assertEquals(AppFeature.State.Home(HomeTabs.State(firstHome, alice)), store.state)
            assertEquals(StoredSession(alice, remember), saved)
        }
    }

    @Test
    fun aNewAccountIsOnboardedThenGoesHome() {
        val nina = MockAccounts.session(MockAccounts.nina.user)
        val store = store(
            AppFeature.State.Auth(AuthFlow.State()),
            sessionClient = SessionClient(save = { _, _ -> }),
            accountClient = AccountClient(fetchProfile = { AccountProfile(needsOnboarding = true) }),
        )
        store.send(AppFeature.Action.Auth(AuthFlow.Action.Delegate.Authenticated(nina, true)))
        assertEquals(AppFeature.State.Onboarding(OnboardingFlow.State(nina)), store.state)
        store.send(AppFeature.Action.Onboarding(OnboardingFlow.Action.Delegate.Finished(nina)))
        assertEquals(AppFeature.State.Home(HomeTabs.State(firstHome, nina)), store.state)
    }

    @Test
    fun aFailedProfileLoadStillGoesHome() {
        val store = store(
            AppFeature.State.Auth(AuthFlow.State()),
            sessionClient = SessionClient(save = { _, _ -> }),
            accountClient = AccountClient(fetchProfile = { throw AccountException(AccountError.NETWORK) }),
        )
        store.send(AppFeature.Action.Auth(AuthFlow.Action.Delegate.Authenticated(alice, true)))
        assertEquals(AppFeature.State.Home(HomeTabs.State(firstHome, alice)), store.state)
    }

    @Test
    fun loggingOutClearsTheSessionAndShowsAFreshAuth() {
        var cleared = false
        val store = store(AppFeature.State.Home(HomeTabs.State(firstHome, alice)), sessionClient = SessionClient(clear = { cleared = true }))
        store.send(AppFeature.Action.Home(HomeTabs.Action.Delegate.LoggedOut))
        assertEquals(AppFeature.State.Auth(AuthFlow.State()), store.state)
        assertTrue(cleared)
    }

    @Test
    fun loginAsSavesTheSessionAndGoesHome() {
        var saved: StoredSession? = null
        val bob = MockAccounts.session(named = "bob")!!
        val store = store(
            AppFeature.State.Auth(AuthFlow.State()),
            sessionClient = SessionClient(save = { session, r -> saved = StoredSession(session, r) }),
            accountClient = AccountClient(fetchProfile = { AccountProfile(needsOnboarding = false) }),
        )
        store.send(AppFeature.Action.LoginAs(bob))
        assertEquals(AppFeature.State.Home(HomeTabs.State(firstHome, bob)), store.state)
        assertEquals(StoredSession(bob, remember = true), saved)
    }

    @Test
    fun resetRelaunchesAndRestoresTheSession() {
        val store = store(AppFeature.State.Home(HomeTabs.State(firstHome, alice)), sessionClient = SessionClient(current = { alice }))
        store.send(AppFeature.Action.Reset)
        assertEquals(AppFeature.State.Launching, store.state)
        store.send(AppFeature.Action.Appeared)
        assertEquals(AppFeature.State.Home(HomeTabs.State(firstHome, alice)), store.state)
    }

    /** Leaving auth cancels what its screens were running, as TCA's `ifCaseLet` does: here the OTP countdown. */
    @Test
    fun leavingAuthCancelsItsEffects() {
        val store = store(
            AppFeature.State.Auth(AuthFlow.State()),
            sessionClient = SessionClient(save = { _, _ -> }),
            accountClient = AccountClient(fetchProfile = { AccountProfile(needsOnboarding = false) }),
            authClient = AuthClient(sendOTP = {}),
        )
        store.send(AppFeature.Action.Auth(AuthFlow.Action.Login(Login.Action.UseOTPTapped)))
        store.send(AppFeature.Action.Auth(AuthFlow.Action.Element(0, AuthFlow.PathAction.OTPLogin(OTPLogin.Action.EmailChanged("alice@example.com")))))
        store.send(AppFeature.Action.Auth(AuthFlow.Action.Element(0, AuthFlow.PathAction.OTPLogin(OTPLogin.Action.SendTapped))))
        assertEquals(1, store.pending)
        store.send(AppFeature.Action.LoginAs(alice))
        assertTrue(store.state is AppFeature.State.Home)
        assertEquals(0, store.pending)
    }

    /**
     * Each choice's usage and refusal, as a failed step prints them (`<usage>: <message>`, CONTRACT.md §1.3): none of
     * the transcripts sends a wrong word, so these pin what the reference prints.
     */
    @Test
    fun choiceCommandsDocumentAndRefuseTheirWords() {
        val commands = HomeTabsAgent.inheritedCommands +
            ShopFeedAgent.commands +
            InterestsAgent.commands +
            CheckoutAgent.commands +
            AppFeatureAgent.inheritedCommands
        fun refusal(name: String): String {
            val command = commands.first { it.name == name }
            val error = assertFailsWith<AgentCommandException> { command.makeAction("nope") }.error
            return "${command.name} ${command.argument}: ${error.message}"
        }
        assertEquals("tab <shop|cart|orders|profile>: invalid argument: expected shop|cart|orders|profile", refusal("tab"))
        assertEquals(
            "filter <all|shoes|bags|watches|jackets|accessories|home>: invalid argument: expected all|shoes|bags|watches|jackets|accessories|home",
            refusal("filter"),
        )
        assertEquals(
            "toggle <shoes|bags|watches|jackets|accessories|home>: invalid argument: expected shoes|bags|watches|jackets|accessories|home",
            refusal("toggle"),
        )
        assertEquals("login-as <alice|bob>: invalid argument: expected alice|bob", refusal("login-as"))
        for (name in listOf("sort", "shipping", "payment")) {
            val refused = refusal(name)
            val words = refused.substringAfter("<").substringBefore(">")
            assertEquals("$name <$words>: invalid argument: expected $words", refused)
        }
        assertEquals(
            "back: nothing to go back to on home/orders",
            "back: " + assertFailsWith<AgentCommandException> {
                AppFeatureAgent.activeScreen(AppFeature.State.Home(HomeTabs.State(UUID(0, 1), MockAccounts.session(MockAccounts.alice.user)).copy(selectedTab = HomeTabs.Tab.ORDERS))).command("back")!!.makeAction(null)
            }.error.message,
        )
    }

    @Test
    fun rootAgentCommands() {
        val launching = AppFeatureAgent.activeScreen(AppFeature.State.Launching)
        assertEquals("launching", launching.path)
        assertEquals(listOf("login-as", "reset", "back"), launching.commands.map { it.name })
        assertEquals(AppFeature.Action.Appeared, launching.appearAction)
        assertEquals(
            AgentCommandError.InvalidArgument("expected alice|bob"),
            assertFailsWith<AgentCommandException> { launching.command("login-as")!!.makeAction("carol") }.error,
        )
        assertEquals(
            AgentCommandError.NotApplicable("nothing to go back to on launching"),
            assertFailsWith<AgentCommandException> { launching.command("back")!!.makeAction(null) }.error,
        )
        val pushed = AppFeatureAgent.activeScreen(AppFeature.State.Auth(AuthFlow.State(path = Stack<AuthFlow.Path>("auth").push(AuthFlow.Path.Register(Register.State())))))
        assertEquals("auth/register", pushed.path)
        assertEquals("AuthFlow", pushed.command("back")?.source)
    }

    @Test
    fun registryCoversEveryScreen() {
        assertEquals(
            listOf(
                "launching", "auth/login", "auth/otp/email", "auth/otp/code", "auth/register", "auth/register/verify",
                "auth/forgot/email", "auth/forgot/reset", "auth/forgot/done",
                "onboarding/welcome", "onboarding/interests", "onboarding/address", "onboarding/notifications",
                "home/shop", "home/shop/<sku>", "home/cart", "home/cart/checkout", "home/cart/confirmation",
                "home/orders", "home/orders/<id>", "home/profile",
            ),
            AppFeatureAgent.registry.map { it.path },
        )
        assertTrue(AppFeatureAgent.registry.all { doc -> doc.commands.any { it.name == "login-as" } })
    }
}
