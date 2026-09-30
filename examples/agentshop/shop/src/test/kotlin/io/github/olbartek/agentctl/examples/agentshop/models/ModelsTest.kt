package io.github.olbartek.agentctl.examples.agentshop.models

import io.github.olbartek.agentctl.examples.agentshop.TestMocks
import io.github.olbartek.agentctl.examples.agentshop.await
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidationTest {
    @Test
    fun validEmails() {
        for (email in listOf("alice@example.com", "a.b+tag@sub.example.co", "x@y.io")) assertTrue(isValidEmail(email), email)
    }

    @Test
    fun invalidEmails() {
        val invalid = listOf(
            "", "alice", "alice@", "@example.com", "alice@example", "alice@example.c", "alice@@example.com",
            "alice@exa mple.com", "alice@example..com", "alice@example.c0m", " alice@example.com",
        )
        for (email in invalid) assertFalse(isValidEmail(email), email)
    }

    @Test
    fun passwordRules() {
        assertEquals(emptyList(), passwordIssues("Passw0rd!"))
        assertEquals(emptyList(), passwordIssues("abcdefg1"))
        assertEquals(listOf(PasswordIssue.TOO_SHORT), passwordIssues("abcdef1"))
        assertEquals(listOf(PasswordIssue.MISSING_DIGIT), passwordIssues("abcdefgh"))
        assertEquals(listOf(PasswordIssue.MISSING_LETTER), passwordIssues("12345678"))
        assertEquals(listOf(PasswordIssue.TOO_SHORT, PasswordIssue.MISSING_LETTER, PasswordIssue.MISSING_DIGIT), passwordIssues(""))
        assertTrue(isStrongPassword("Hunter22x"))
        assertFalse(isStrongPassword("hunter"))
    }

    @Test
    fun validationIssues() {
        assertEquals(
            listOf(ValidationIssue.PASSWORD_TOO_SHORT, ValidationIssue.PASSWORD_MISSING_DIGIT),
            passwordIssues("abc").map(ValidationIssue::of),
        )
        assertEquals("none", ValidationIssue.summary(emptyList()))
        assertEquals("email,confirmMismatch", ValidationIssue.summary(listOf(ValidationIssue.EMAIL, ValidationIssue.CONFIRM_MISMATCH)))
    }

    @Test
    fun zipsAndCards() {
        for (zip in listOf("10001", "97201")) assertTrue(isValidZip(zip), zip)
        // The last one is five full-width digits: numbers, but not ASCII.
        for (zip in listOf("", "1000", "100011", "1000a", "１２３４５")) assertFalse(isValidZip(zip), zip)
        assertTrue(isValidCardNumber("4242 4242 4242 4242"))
        assertTrue(isValidCardNumber("4000000000000002"))
        assertFalse(isValidCardNumber("4242"))
        assertFalse(isValidCardNumber("4242-4242-4242-4242"))
    }

    @Test
    fun codeFieldsKeepSixDigits() {
        assertEquals("123456", "12a34 5678".digitsPrefix(6))
        assertEquals("", "abc".digitsPrefix(6))
    }
}

class FormattingTest {
    @Test
    fun cents() {
        assertEquals("$0.00", formatCents(0))
        assertEquals("$0.05", formatCents(5))
        assertEquals("$1.00", formatCents(100))
        assertEquals("$59.80", formatCents(5980))
        assertEquals("$1234.56", formatCents(123_456))
        assertEquals("-$2.50", formatCents(-250))
    }

    @Test
    fun days() {
        assertEquals("2025-12-30", formatDay(day("2025-12-30")))
        assertEquals(Instant.ofEpochSecond(1_767_225_600), day("2026-01-01"))
    }
}

class OrderTest {
    @Test
    fun totalsAndCancellability() {
        val order = Order(
            1003,
            OrderStatus.PENDING,
            day("2025-12-30"),
            listOf(OrderItem("Laptop Stand", 1, 3990), OrderItem("USB-C Cable", 2, 950)),
        )
        assertEquals(5890, order.totalCents)
        assertTrue(order.isCancellable)
        assertFalse(order.copy(status = OrderStatus.SHIPPED).isCancellable)
    }

    @Test
    fun errorCodesAreTheReferencesRawValues() {
        assertEquals(AuthError.ACCOUNT_LOCKED, codeOf<AuthError>("accountLocked"))
        assertEquals("notCancellable", OrdersError.NOT_CANCELLABLE.code)
        assertEquals(listOf("shoes", "bags", "watches", "jackets", "accessories", "home"), ProductCategory.entries.map { it.code })
    }

    @Test
    fun errorWrapping() {
        assertEquals(AuthError.EMAIL_TAKEN, AuthError.of(AuthException(AuthError.EMAIL_TAKEN)))
        assertEquals(AuthError.NETWORK, AuthError.of(IllegalStateException()))
        assertEquals(OrdersError.NOT_FOUND, OrdersError.of(OrdersException(OrdersError.NOT_FOUND)))
        assertEquals(OrdersError.NETWORK, OrdersError.of(IllegalStateException()))
    }
}

class ScheduledFaultsTest {
    @Test
    fun firesOnTheScheduledCallOnly() {
        val faults = ScheduledFaults(listOf("orders.fetchOrders#2=network"))
        assertNull(faults.next("orders.fetchOrders"))
        assertEquals("network", faults.next("orders.fetchOrders"))
        assertNull(faults.next("orders.fetchOrders"))
    }

    @Test
    fun countsEachMethodSeparately() {
        val faults = ScheduledFaults(listOf("a.one#1=timeout", "a.two#2=network"))
        assertNull(faults.next("a.two"))
        assertEquals("timeout", faults.next("a.one"))
        assertEquals("network", faults.next("a.two"))
    }

    @Test
    fun ignoresMalformedSpecs() {
        for (spec in listOf("a.one=network", "a.one#x=network", "a.one#1", "#1=network", "a.one#1=")) {
            val faults = ScheduledFaults(listOf(spec))
            assertNull(faults.next("a.one"), spec)
            assertNull(faults.next(""), spec)
        }
    }

    @Test
    fun readsTheReferencesLaunchArguments() {
        val faults = ScheduledFaults.fromArguments(listOf("AgentShop", "-mock-fault", "a.one#1=network", "-agent-port", "0", "-mock-fault"))
        assertEquals("network", faults.next("a.one"))
        assertNull(faults.next("-agent-port"))
    }

    private class Failure(val code: String) : Exception(code)

    @Test
    fun shopCallArmsTheFaultForThatCall() {
        val mocks = TestMocks()
        val calls = ShopCalls(mocks.backend, ScheduledFaults(listOf("a.one#2=network")))
        suspend fun call(): Int = calls.call("a.one", { Failure(it) }) { 42 }
        assertEquals(42, await { call() })
        assertEquals("network", assertFailsWith<Failure> { await { call() } }.code)
        assertEquals(42, await { call() })
        assertEquals(listOf("a.one", "a.one", "a.one"), mocks.log.all)
    }
}
