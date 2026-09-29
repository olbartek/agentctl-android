package io.github.olbartek.agentctl.examples.agentshop.clients

import io.github.olbartek.agentctl.examples.agentshop.TestMocks
import io.github.olbartek.agentctl.examples.agentshop.TestTime
import io.github.olbartek.agentctl.examples.agentshop.await
import io.github.olbartek.agentctl.examples.agentshop.models.AccountError
import io.github.olbartek.agentctl.examples.agentshop.models.AccountException
import io.github.olbartek.agentctl.examples.agentshop.models.AccountProfile
import io.github.olbartek.agentctl.examples.agentshop.models.Address
import io.github.olbartek.agentctl.examples.agentshop.models.AuthError
import io.github.olbartek.agentctl.examples.agentshop.models.AuthException
import io.github.olbartek.agentctl.examples.agentshop.models.CartError
import io.github.olbartek.agentctl.examples.agentshop.models.CartException
import io.github.olbartek.agentctl.examples.agentshop.models.CartLine
import io.github.olbartek.agentctl.examples.agentshop.models.OnboardingAnswers
import io.github.olbartek.agentctl.examples.agentshop.models.OrderRequest
import io.github.olbartek.agentctl.examples.agentshop.models.OrderStatus
import io.github.olbartek.agentctl.examples.agentshop.models.OrdersError
import io.github.olbartek.agentctl.examples.agentshop.models.OrdersException
import io.github.olbartek.agentctl.examples.agentshop.models.ProductCategory
import io.github.olbartek.agentctl.examples.agentshop.models.User
import io.github.olbartek.agentctl.examples.agentshop.models.day
import io.github.olbartek.agentctl.examples.agentshop.models.formatCents
import java.nio.file.Files
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private fun expectAuth(error: AuthError, block: () -> Unit) = assertEquals(error, assertFailsWith<AuthException> { block() }.error)

private fun expectOrders(error: OrdersError, block: () -> Unit) = assertEquals(error, assertFailsWith<OrdersException> { block() }.error)

class AuthBackendLoginTest {
    private val backend = AuthBackend(TestTime().uptime)

    @Test
    fun seededAccountsLogIn() {
        val alice = backend.login("alice@example.com", "Passw0rd!")
        assertEquals("Alice", alice.user.name)
        assertEquals(MockAccounts.session(named = "alice"), alice)
        assertEquals("bob@example.com", backend.login(" Bob@Example.com ", "Hunter22x").user.email)
    }

    @Test
    fun wrongPasswordAndUnknownEmail() {
        expectAuth(AuthError.INVALID_CREDENTIALS) { backend.login("alice@example.com", "nope") }
        expectAuth(AuthError.INVALID_CREDENTIALS) { backend.login("nobody@example.com", "Passw0rd!") }
    }

    @Test
    fun fifthFailureLocksTheAccount() {
        repeat(4) { expectAuth(AuthError.INVALID_CREDENTIALS) { backend.login("alice@example.com", "nope") } }
        expectAuth(AuthError.ACCOUNT_LOCKED) { backend.login("alice@example.com", "nope") }
        expectAuth(AuthError.ACCOUNT_LOCKED) { backend.login("alice@example.com", "Passw0rd!") }
        // Other accounts are unaffected.
        backend.login("bob@example.com", "Hunter22x")
    }

    @Test
    fun successResetsTheFailureCount() {
        repeat(4) { runCatching { backend.login("alice@example.com", "nope") } }
        backend.login("alice@example.com", "Passw0rd!")
        expectAuth(AuthError.INVALID_CREDENTIALS) { backend.login("alice@example.com", "nope") }
    }

    @Test
    fun lockedAccountIsAlwaysLocked() {
        expectAuth(AuthError.ACCOUNT_LOCKED) { backend.login("locked@example.com", "anything") }
    }
}

class AuthBackendOTPTest {
    private val time = TestTime()
    private val backend = AuthBackend(time.uptime)

    @Test
    fun sendAndVerify() {
        backend.sendOTP("alice@example.com")
        assertEquals("Alice", backend.verifyOTP("alice@example.com", "123456").user.name)
        // The code is single use.
        expectAuth(AuthError.INVALID_CODE) { backend.verifyOTP("alice@example.com", "123456") }
    }

    @Test
    fun unknownEmailAndNoCode() {
        expectAuth(AuthError.UNKNOWN_EMAIL) { backend.sendOTP("nobody@example.com") }
        expectAuth(AuthError.INVALID_CODE) { backend.verifyOTP("alice@example.com", "123456") }
    }

    @Test
    fun resendCooldown() {
        backend.sendOTP("alice@example.com")
        time.advance(29.seconds)
        expectAuth(AuthError.RESEND_NOT_AVAILABLE) { backend.sendOTP("alice@example.com") }
        time.advance(1.seconds)
        backend.sendOTP("alice@example.com")
    }

    @Test
    fun threeWrongCodesRequireAResend() {
        backend.sendOTP("alice@example.com")
        repeat(3) { expectAuth(AuthError.INVALID_CODE) { backend.verifyOTP("alice@example.com", "000000") } }
        expectAuth(AuthError.INVALID_CODE) { backend.verifyOTP("alice@example.com", "123456") }
        time.advance(30.seconds)
        backend.sendOTP("alice@example.com")
        backend.verifyOTP("alice@example.com", "123456")
    }

    @Test
    fun codesExpireAfterFiveMinutes() {
        backend.sendOTP("alice@example.com")
        time.advance(299.seconds)
        expectAuth(AuthError.INVALID_CODE) { backend.verifyOTP("alice@example.com", "000000") }
        time.advance(1.seconds)
        expectAuth(AuthError.CODE_EXPIRED) { backend.verifyOTP("alice@example.com", "123456") }
    }
}

class AuthBackendRegisterAndResetTest {
    private val time = TestTime()
    private val backend = AuthBackend(time.uptime)

    @Test
    fun registerVerifyThenLogIn() {
        backend.register("Carol", "Carol@Example.com", "Secret123")
        expectAuth(AuthError.EMAIL_NOT_VERIFIED) { backend.login("carol@example.com", "Secret123") }
        expectAuth(AuthError.INVALID_CODE) { backend.verifyEmail("carol@example.com", "000000") }
        val session = backend.verifyEmail("carol@example.com", "123456")
        assertEquals(User("u6", "Carol", "carol@example.com"), session.user)
        assertEquals(session, backend.login("carol@example.com", "Secret123"))
    }

    @Test
    fun registerErrors() {
        expectAuth(AuthError.EMAIL_TAKEN) { backend.register("A", "alice@example.com", "Secret123") }
        expectAuth(AuthError.WEAK_PASSWORD) { backend.register("C", "carol@example.com", "short") }
    }

    @Test
    fun verificationCodesExpireAndResendHasACooldown() {
        backend.register("Carol", "carol@example.com", "Secret123")
        expectAuth(AuthError.RESEND_NOT_AVAILABLE) { backend.resendVerification("carol@example.com") }
        time.advance(300.seconds)
        expectAuth(AuthError.CODE_EXPIRED) { backend.verifyEmail("carol@example.com", "123456") }
        backend.resendVerification("carol@example.com")
        backend.verifyEmail("carol@example.com", "123456")
        expectAuth(AuthError.INVALID_CODE) { backend.resendVerification("carol@example.com") }
    }

    @Test
    fun unverifiedAccountsCannotUseOneTimeCodes() {
        backend.register("Carol", "carol@example.com", "Secret123")
        expectAuth(AuthError.EMAIL_NOT_VERIFIED) { backend.sendOTP("carol@example.com") }
    }

    @Test
    fun googleSignInIsTheGoogleTestAccount() {
        val session = backend.signInWithGoogle()
        assertEquals(MockAccounts.google.user, session.user)
        assertEquals(session, backend.signInWithGoogle())
        expectAuth(AuthError.INVALID_CREDENTIALS) { backend.login("becca@gmail.com", "anything1") }
    }

    @Test
    fun resetFlow() {
        backend.requestPasswordReset("alice@example.com")
        expectAuth(AuthError.INVALID_CODE) { backend.resetPassword("alice@example.com", "000000", "NewPass123") }
        expectAuth(AuthError.WEAK_PASSWORD) { backend.resetPassword("alice@example.com", "654321", "weak") }
        backend.resetPassword("alice@example.com", "654321", "NewPass123")
        backend.login("alice@example.com", "NewPass123")
        expectAuth(AuthError.INVALID_CREDENTIALS) { backend.login("alice@example.com", "Passw0rd!") }
    }

    @Test
    fun resetUnlocksALockedAccount() {
        repeat(5) { runCatching { backend.login("alice@example.com", "nope") } }
        backend.requestPasswordReset("alice@example.com")
        backend.resetPassword("alice@example.com", "654321", "NewPass123")
        backend.login("alice@example.com", "NewPass123")
    }

    @Test
    fun unknownEmailRequestSucceedsButIssuesNoCode() {
        backend.requestPasswordReset("nobody@example.com")
        expectAuth(AuthError.INVALID_CODE) { backend.resetPassword("nobody@example.com", "654321", "NewPass123") }
    }

    @Test
    fun resetCodesExpire() {
        backend.requestPasswordReset("alice@example.com")
        time.advance(300.seconds)
        expectAuth(AuthError.CODE_EXPIRED) { backend.resetPassword("alice@example.com", "654321", "NewPass123") }
    }
}

/** The live client is the mock shim: it logs calls, honours faults, and delegates to the backend. */
class AuthClientLiveTest {
    private val mocks = TestMocks()
    private val client = AuthClient.live(mocks.calls, AuthBackend(TestTime().uptime))

    @Test
    fun logsCallsAndDelegatesToTheBackend() {
        await {
            assertEquals("Alice", client.login("alice@example.com", "Passw0rd!").user.name)
            client.sendOTP("bob@example.com")
            client.verifyOTP("bob@example.com", "123456")
            client.requestPasswordReset("bob@example.com")
            client.resetPassword("bob@example.com", "654321", "Another1x")
            client.register("Dan", "dan@example.com", "Secret123")
            client.verifyEmail("dan@example.com", "123456")
            client.signInWithGoogle()
        }
        assertEquals(
            listOf(
                "auth.login", "auth.sendOTP", "auth.verifyOTP", "auth.requestPasswordReset", "auth.resetPassword",
                "auth.register", "auth.verifyEmail", "auth.signInWithGoogle",
            ),
            mocks.log.all,
        )
    }

    @Test
    fun faultsBecomeTypedErrorsAndBackendErrorsPassThrough() {
        mocks.faults.set("auth.login", "network")
        expectAuth(AuthError.NETWORK) { await { client.login("alice@example.com", "Passw0rd!") } }
        assertEquals("Alice", await { client.login("alice@example.com", "Passw0rd!") }.user.name)
        expectAuth(AuthError.ACCOUNT_LOCKED) { await { client.login("locked@example.com", "x") } }
    }

    @Test
    fun mockMethods() {
        assertTrue(AuthClient.mockMethods.map { it.name }.contains("auth.verifyOTP"))
        assertTrue(AuthClient.mockMethods[0].errorCodes.contains("network"))
    }
}

class SessionTest {
    private val alice = MockAccounts.session(MockAccounts.alice.user)

    @Test
    fun saveCurrentClearAreLogged() {
        val mocks = TestMocks()
        val client = SessionClient.live(mocks.calls, SessionStorage.inMemory())
        await {
            assertNull(client.current())
            client.save(alice, true)
            assertEquals(alice, client.current())
            client.clear()
            assertNull(client.current())
        }
        assertEquals(listOf("session.current", "session.save", "session.current", "session.clear", "session.current"), mocks.log.all)
    }

    /** "Keep me signed in" off: the session works until relaunch, when `current()` drops it. */
    @Test
    fun sessionsNotRememberedAreNotRestored() {
        val storage = SessionStorage.inMemory()
        val client = SessionClient.live(TestMocks().calls, storage)
        await { client.save(alice, false) }
        assertEquals(alice, storage.currentSession)
        assertNull(await { client.current() })
        assertNull(storage.currentSession)
    }

    @Test
    fun fileStorageRoundTrips() {
        val directory = Files.createTempDirectory("agentshop")
        try {
            val file = directory.resolve("session/session.properties").toFile()
            val storage = SessionStorage.file(file)
            val stored = StoredSession(alice.copy(user = alice.user.copy(name = "Alice = Liddell")), remember = true)
            assertNull(storage.load())
            storage.store(stored)
            assertEquals(stored, storage.load())
            // A second instance reads the same file, as a relaunched app would.
            assertEquals(stored, SessionStorage.file(file).load())
            storage.store(null)
            assertNull(storage.load())
            assertFalse(file.exists())
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            directory.deleteRecursively()
        }
    }

    @Test
    fun inMemoryStorage() {
        val storage = SessionStorage.inMemory(StoredSession(alice, remember = false))
        assertEquals(alice, storage.currentSession)
        storage.store(null)
        assertNull(storage.load())
    }
}

class OrdersTest {
    private val alice = MockAccounts.session(MockAccounts.alice.user)

    @Test
    fun seededOrders() {
        val backend = OrdersBackend()
        val orders = backend.orders("alice@example.com")
        assertEquals(listOf(1001, 1002, 1003), orders.map { it.id })
        assertEquals(listOf(OrderStatus.DELIVERED, OrderStatus.SHIPPED, OrderStatus.PENDING), orders.map { it.status })
        assertEquals(listOf("$43.99", "$89.00", "$59.80"), orders.map { formatCents(it.totalCents) })
        assertTrue(backend.orders("bob@example.com").isEmpty())
    }

    @Test
    fun fetchOneAndCancel() {
        val backend = OrdersBackend()
        assertEquals(OrderStatus.SHIPPED, backend.order(1002, "alice@example.com").status)
        expectOrders(OrdersError.NOT_FOUND) { backend.order(9999, "alice@example.com") }
        expectOrders(OrdersError.NOT_FOUND) { backend.order(1001, "bob@example.com") }
        assertEquals(OrderStatus.CANCELLED, backend.cancelOrder(1003, "alice@example.com").status)
        assertEquals(OrderStatus.CANCELLED, backend.order(1003, "alice@example.com").status)
        expectOrders(OrdersError.NOT_CANCELLABLE) { backend.cancelOrder(1003, "alice@example.com") }
        expectOrders(OrdersError.NOT_CANCELLABLE) { backend.cancelOrder(1002, "alice@example.com") }
        expectOrders(OrdersError.NOT_FOUND) { backend.cancelOrder(9999, "alice@example.com") }
    }

    @Test
    fun placedOrdersGetTheNextIDAndCarryShippingAndDiscount() {
        val backend = OrdersBackend()
        val request = OrderRequest(
            lines = listOf(CartLine(Catalog.products.first { it.id == 101 }, "42", 2)),
            address = MockProfiles.aliceAddress,
            shippingCents = 1500,
            discountCents = 1780,
            cardNumber = "4242 4242 4242 4242",
        )
        val order = backend.placeOrder(request, "alice@example.com", day("2026-01-01"))
        assertEquals(1004, order.id)
        assertEquals(listOf("Trail Runner (42)", "Express shipping", "Promo discount"), order.items.map { it.name })
        assertEquals("$175.20", formatCents(order.totalCents))
        assertEquals(1001, backend.placeOrder(request, "bob@example.com", day("2026-01-01")).id)
        expectOrders(OrdersError.PAYMENT_DECLINED) {
            backend.placeOrder(request.copy(cardNumber = "4000 0000 0000 0002"), "alice@example.com", day("2026-01-01"))
        }
    }

    @Test
    fun theLiveClientUsesTheSignedInUserWithoutLoggingASessionCall() {
        val mocks = TestMocks()
        val client = OrdersClient.live(mocks.calls, SessionStorage.inMemory(StoredSession(alice, true)), OrdersBackend(), mocks.backend.clock)
        await {
            assertEquals(3, client.fetchOrders().size)
            assertEquals(OrderStatus.DELIVERED, client.fetchOrder(1001).status)
            assertEquals(OrderStatus.CANCELLED, client.cancelOrder(1003).status)
        }
        assertEquals(listOf("orders.fetchOrders", "orders.fetchOrder", "orders.cancelOrder"), mocks.log.all)
    }

    @Test
    fun signedOutIsUnauthorizedAndFaultsAreTyped() {
        val mocks = TestMocks()
        val signedOut = OrdersClient.live(mocks.calls, SessionStorage.inMemory(), OrdersBackend(), mocks.backend.clock)
        expectOrders(OrdersError.UNAUTHORIZED) { await { signedOut.fetchOrders() } }

        val client = OrdersClient.live(mocks.calls, SessionStorage.inMemory(StoredSession(alice, true)), OrdersBackend(), mocks.backend.clock)
        mocks.faults.set("orders.fetchOrders", "network")
        mocks.faults.set("orders.cancelOrder", "notCancellable")
        expectOrders(OrdersError.NETWORK) { await { client.fetchOrders() } }
        expectOrders(OrdersError.NOT_CANCELLABLE) { await { client.cancelOrder(1003) } }
        assertEquals(3, await { client.fetchOrders() }.size)
        assertEquals(
            listOf("orders.fetchOrders", "orders.fetchOrder", "orders.cancelOrder", "orders.placeOrder"),
            OrdersClient.mockMethods.map { it.name },
        )
    }
}

class AccountTest {
    @Test
    fun seededAccountsAreOnboarded() {
        val backend = AccountBackend()
        val alice = backend.profile("alice@example.com")
        assertFalse(alice.needsOnboarding)
        assertEquals(MockProfiles.aliceAddress, alice.address)
        assertFalse(backend.profile("bob@example.com").needsOnboarding)
    }

    @Test
    fun unknownAccountsNeedOnboardingUntilTheyComplete() {
        val backend = AccountBackend()
        assertTrue(backend.profile("nina@example.com").needsOnboarding)
        val address = Address("Nina", "2 Elm St", "Portland", "97201")
        val saved = backend.completeOnboarding(OnboardingAnswers(listOf(ProductCategory.BAGS, ProductCategory.HOME), address, true), "nina@example.com")
        assertEquals(AccountProfile(false, listOf(ProductCategory.BAGS, ProductCategory.HOME), address), saved)
        assertEquals(saved, backend.profile("nina@example.com"))
    }

    @Test
    fun theLiveClientNeedsASession() {
        val mocks = TestMocks()
        val client = AccountClient.live(mocks.calls, SessionStorage.inMemory(), AccountBackend())
        assertEquals(AccountError.UNAUTHORIZED, assertFailsWith<AccountException> { await { client.fetchProfile() } }.error)
        assertEquals(listOf("account.fetchProfile"), mocks.log.all)
    }
}

class CatalogAndCartTest {
    @Test
    fun twelveProductsTwoPerCategory() {
        assertEquals((101..112).toList(), Catalog.products.map { it.id })
        assertEquals(ProductCategory.entries.toSet(), Catalog.products.groupBy { it.category }.filterValues { it.size == 2 }.keys)
    }

    @Test
    fun promoCodesAreNormalizedAndCheckedByTheServer() {
        val mocks = TestMocks()
        val client = CartClient.live(mocks.calls)
        assertEquals(Promo("SAVE10", 10), await { client.applyPromo(" save10 ") })
        assertEquals(CartError.INVALID_PROMO, assertFailsWith<CartException> { await { client.applyPromo("FREE") } }.error)
        assertEquals(1780, Promo("SAVE10", 10).discount(17800))
        assertEquals(listOf("cart.applyPromo", "cart.applyPromo"), mocks.log.all)
    }
}
