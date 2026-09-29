package io.github.olbartek.agentctl.examples.agentshop.shop

import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.examples.agentshop.TestMocks
import io.github.olbartek.agentctl.examples.agentshop.TestStore
import io.github.olbartek.agentctl.examples.agentshop.clients.AccountClient
import io.github.olbartek.agentctl.examples.agentshop.clients.CartClient
import io.github.olbartek.agentctl.examples.agentshop.clients.Catalog
import io.github.olbartek.agentctl.examples.agentshop.clients.CatalogClient
import io.github.olbartek.agentctl.examples.agentshop.clients.MockProfiles
import io.github.olbartek.agentctl.examples.agentshop.clients.OrdersClient
import io.github.olbartek.agentctl.examples.agentshop.clients.Promo
import io.github.olbartek.agentctl.examples.agentshop.models.AccountProfile
import io.github.olbartek.agentctl.examples.agentshop.models.Address
import io.github.olbartek.agentctl.examples.agentshop.models.CartError
import io.github.olbartek.agentctl.examples.agentshop.models.CartLine
import io.github.olbartek.agentctl.examples.agentshop.models.CatalogError
import io.github.olbartek.agentctl.examples.agentshop.models.CatalogException
import io.github.olbartek.agentctl.examples.agentshop.models.Order
import io.github.olbartek.agentctl.examples.agentshop.models.OrderRequest
import io.github.olbartek.agentctl.examples.agentshop.models.OrderStatus
import io.github.olbartek.agentctl.examples.agentshop.models.OrdersError
import io.github.olbartek.agentctl.examples.agentshop.models.OrdersException
import io.github.olbartek.agentctl.examples.agentshop.models.ProductCategory
import io.github.olbartek.agentctl.examples.agentshop.models.day
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val trailRunner = Catalog.products.first { it.id == 101 }
private val tote = Catalog.products.first { it.id == 103 }
private val beanie = Catalog.products.first { it.id == 109 }

class ShopFeedTest {
    @Test
    fun filterSearchAndSort() {
        val state = ShopFeed.State(products = Catalog.products)
        assertEquals(12, state.visible.size)
        assertEquals(listOf(105, 106), state.copy(filter = ProductCategory.WATCHES).visible.map { it.id })
        assertEquals(listOf(107, 108), state.copy(query = " JACKET ").visible.map { it.id })
        assertEquals(listOf(108), state.copy(query = "down", filter = ProductCategory.JACKETS).visible.map { it.id })
        assertTrue(state.copy(query = "zzz").visible.isEmpty())
        assertEquals(listOf(111, 109, 103), state.copy(sort = ShopFeed.Sort.PRICE_ASC).visible.take(3).map { it.id })
        assertEquals(listOf(106, 108, 105), state.copy(sort = ShopFeed.Sort.PRICE_DESC).visible.take(3).map { it.id })
    }

    @Test
    fun loadsOnceAndRefreshFailureKeepsProducts() {
        var fail = false
        val client = CatalogClient(fetchProducts = { if (fail) throw CatalogException(CatalogError.TIMEOUT) else Catalog.products })
        val store = TestStore(ShopFeed.State(), reducer = ShopFeed.reducer(client))
        store.send(ShopFeed.Action.OnAppear)
        assertEquals(ShopFeed.State(products = Catalog.products, hasLoaded = true), store.state)
        store.send(ShopFeed.Action.OnAppear)
        assertEquals(1, store.received.count { it is ShopFeed.Action.ProductsResponse }, "it loads once")
        fail = true
        store.send(ShopFeed.Action.Refresh)
        assertEquals(ShopFeed.State(products = Catalog.products, hasLoaded = true, error = CatalogError.TIMEOUT), store.state)
        assertNull(ShopFeedAgent.activeScreen(store.state).command("retry")?.disabledReason)
    }

    @Test
    fun openingAProduct() {
        val store = TestStore(ShopFeed.State(products = Catalog.products), reducer = ShopFeed.reducer(CatalogClient()))
        store.send(ShopFeed.Action.ProductTapped(999))
        assertFalse(store.received.any { it is ShopFeed.Action.Delegate })
        store.send(ShopFeed.Action.ProductTapped(101))
        assertEquals(ShopFeed.Action.Delegate.OpenProduct(trailRunner), store.received.last())
        assertEquals("products=0", ShopFeedAgent.activeScreen(ShopFeed.State()).command("open")?.disabledReason)
    }
}

class ProductDetailTest {
    @Test
    fun sizeIsRequiredAndQuantityIsBounded() {
        val store = TestStore(ProductDetail.State(trailRunner), reducer = ProductDetail.reducer)
        assertFalse(store.state.canAdd)
        store.send(ProductDetail.Action.SizeTapped("99"))
        assertNull(store.state.size)
        store.send(ProductDetail.Action.SizeTapped("42"))
        assertTrue(store.state.canAdd)
        store.send(ProductDetail.Action.QuantityDownTapped)
        assertEquals(ProductDetail.Error.MIN_QUANTITY, store.state.error)
        repeat(4) { store.send(ProductDetail.Action.QuantityUpTapped) }
        assertEquals(5, store.state.quantity)
        assertNull(store.state.error)
        store.send(ProductDetail.Action.QuantityUpTapped)
        assertEquals(ProductDetail.Error.MAX_QUANTITY, store.state.error)
        store.send(ProductDetail.Action.AddToCartTapped)
        assertEquals(5, store.state.added)
        assertNull(store.state.error)
        assertEquals(ProductDetail.Action.Delegate.Add(CartLine(trailRunner, "42", 5)), store.received.last())
        assertTrue(SummaryItem("price", "$89.00") in ProductDetailAgent.activeScreen(store.state).summary)
    }

    @Test
    fun outOfStockCannotBeAdded() {
        val state = ProductDetail.State(beanie)
        assertFalse(state.canAdd)
        assertEquals("canAdd=false", ProductDetailAgent.activeScreen(state).command("add-to-cart")?.disabledReason)
        assertTrue(SummaryItem("size", "one-size") in ProductDetailAgent.activeScreen(state).summary)
    }
}

class CartTest {
    @Test
    fun linesMergeAndAdjust() {
        val store = TestStore(Cart.State(), reducer = Cart.reducer(CartClient()))
        store.send(Cart.Action.Add(CartLine(tote, null, 1)))
            .send(Cart.Action.Add(CartLine(tote, null, 2)))
            .send(Cart.Action.Add(CartLine(trailRunner, "42", 1)))
        assertEquals(listOf(CartLine(tote, null, 3), CartLine(trailRunner, "42", 1)), store.state.lines)
        assertEquals(4, store.state.itemCount)
        assertEquals(3 * 2900 + 8900, store.state.subtotalCents)
        store.send(Cart.Action.DecrementTapped("101-42"))
        assertEquals(listOf("103"), store.state.lines.map { it.id })
        store.send(Cart.Action.IncrementTapped("103"))
        assertEquals(4, store.state.lines.single().quantity)
        store.send(Cart.Action.RemoveTapped("103"))
        assertFalse(store.state.canCheckout)
    }

    @Test
    fun promoValidThenInvalid() {
        val mocks = TestMocks()
        val store = TestStore(Cart.State(lines = listOf(CartLine(trailRunner, "42", 2))), reducer = Cart.reducer(CartClient.live(mocks.calls)))
        store.send(Cart.Action.PromoCodeChanged("save10")).send(Cart.Action.ApplyPromoTapped)
        assertEquals(Promo("SAVE10", 10), store.state.promo)
        assertEquals("", store.state.promoCode)
        assertEquals(17800 - 1780, store.state.totalCents)
        store.send(Cart.Action.PromoCodeChanged("FREE")).send(Cart.Action.ApplyPromoTapped)
        assertEquals(CartError.INVALID_PROMO, store.state.error)
        assertEquals("SAVE10", store.state.promo?.code)
        assertFalse(store.state.isApplyingPromo)
        // Typing clears the error; clearing the promo removes the discount.
        store.send(Cart.Action.PromoCodeChanged("")).send(Cart.Action.ClearPromoTapped)
        assertNull(store.state.error)
        assertEquals(0, store.state.discountCents)
        assertEquals("no code typed", CartAgent.activeScreen(store.state).command("apply-promo")?.disabledReason)
        assertEquals(listOf("cart.applyPromo", "cart.applyPromo"), mocks.log.all)
    }
}

class CheckoutTest {
    private val lines = listOf(CartLine(tote, null, 2))
    private val address = Address("Bob", "3 Oak Ave", "Austin", "73301")

    @Test
    fun prefillsFromTheAccountOnlyWhenUntouched() {
        var fetched = 0
        val client = AccountClient(fetchProfile = { fetched += 1; AccountProfile(false, address = MockProfiles.aliceAddress) })
        val store = TestStore(Checkout.State(lines), reducer = Checkout.reducer(client, OrdersClient()))
        store.send(Checkout.Action.OnAppear)
        assertEquals(MockProfiles.aliceAddress, store.state.address)
        store.send(Checkout.Action.OnAppear)
        assertEquals(1, fetched)

        val typed = TestStore(Checkout.State(lines, address = address), reducer = Checkout.reducer(client, OrdersClient()))
        assertEquals(address, typed.send(Checkout.Action.OnAppear).state.address, "never overwrite what the shopper typed")
    }

    @Test
    fun validationThenDeclineThenApplePay() {
        val placed = mutableListOf<OrderRequest>()
        val order = Order(1001, OrderStatus.PENDING, day("2026-01-01"), emptyList())
        val orders = OrdersClient(placeOrder = { request ->
            placed.add(request)
            if (request.cardNumber != null) throw OrdersException(OrdersError.PAYMENT_DECLINED)
            order
        })
        val store = TestStore(Checkout.State(lines, address = address.copy(zip = "7330")), reducer = Checkout.reducer(AccountClient(), orders))
        assertFalse(store.state.canPlace)
        store.send(Checkout.Action.CardNumberChanged("4242")).send(Checkout.Action.PlaceOrderTapped)
        assertEquals(Checkout.Error.INVALID_ZIP, store.state.error)
        store.send(Checkout.Action.ZipChanged("73301"))
        assertNull(store.state.error)
        store.send(Checkout.Action.PlaceOrderTapped)
        assertEquals(Checkout.Error.INVALID_CARD, store.state.error)
        store.send(Checkout.Action.CardNumberChanged("4000 0000 0000 0002")).send(Checkout.Action.ShippingTapped(Checkout.Shipping.EXPRESS))
        assertEquals(2 * 2900 + 1500, store.state.totalCents)
        store.send(Checkout.Action.PlaceOrderTapped)
        assertEquals(Checkout.Error.PAYMENT_DECLINED, store.state.error)
        assertFalse(store.state.isPlacing)
        store.send(Checkout.Action.PaymentTapped(Checkout.Payment.APPLE_PAY))
        assertNull(store.state.error)
        assertEquals("payment=apple-pay", CheckoutAgent.activeScreen(store.state).command("card")?.disabledReason)
        store.send(Checkout.Action.PlaceOrderTapped)
        assertEquals(Checkout.Action.Delegate.Placed(order), store.received.last())
        assertEquals(listOf("4000 0000 0000 0002", null), placed.map { it.cardNumber })
        assertEquals(1500, placed.last().shippingCents)
    }

    @Test
    fun anyOtherServerFailureIsNetwork() {
        val orders = OrdersClient(placeOrder = { throw OrdersException(OrdersError.UNAUTHORIZED) })
        val store = TestStore(Checkout.State(lines, address = address, payment = Checkout.Payment.APPLE_PAY), reducer = Checkout.reducer(AccountClient(), orders))
        assertEquals(Checkout.Error.NETWORK, store.send(Checkout.Action.PlaceOrderTapped).state.error)
    }

    @Test
    fun confirmation() {
        val order = Order(1004, OrderStatus.PENDING, day("2026-01-01"), emptyList())
        val store = TestStore(OrderConfirmation.State(order), reducer = OrderConfirmation.reducer)
        store.send(OrderConfirmation.Action.ViewOrderTapped).send(OrderConfirmation.Action.ContinueShoppingTapped)
        assertEquals(
            listOf(OrderConfirmation.Action.Delegate.ViewOrder(1004), OrderConfirmation.Action.Delegate.ContinueShopping),
            store.received.filterIsInstance<OrderConfirmation.Action.Delegate>(),
        )
    }
}
