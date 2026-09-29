package io.github.olbartek.agentctl.examples.agentshop.home

import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.examples.agentshop.TestStore
import io.github.olbartek.agentctl.examples.agentshop.clients.AccountClient
import io.github.olbartek.agentctl.examples.agentshop.clients.CartClient
import io.github.olbartek.agentctl.examples.agentshop.clients.Catalog
import io.github.olbartek.agentctl.examples.agentshop.clients.CatalogClient
import io.github.olbartek.agentctl.examples.agentshop.clients.MockAccounts
import io.github.olbartek.agentctl.examples.agentshop.clients.MockOrders
import io.github.olbartek.agentctl.examples.agentshop.clients.OrdersClient
import io.github.olbartek.agentctl.examples.agentshop.models.CartLine
import io.github.olbartek.agentctl.examples.agentshop.models.Order
import io.github.olbartek.agentctl.examples.agentshop.models.OrderStatus
import io.github.olbartek.agentctl.examples.agentshop.models.OrdersError
import io.github.olbartek.agentctl.examples.agentshop.models.OrdersException
import io.github.olbartek.agentctl.examples.agentshop.models.day
import io.github.olbartek.agentctl.examples.agentshop.shop.Cart
import io.github.olbartek.agentctl.examples.agentshop.shop.Checkout
import io.github.olbartek.agentctl.examples.agentshop.shop.OrderConfirmation
import io.github.olbartek.agentctl.examples.agentshop.shop.ProductDetail
import io.github.olbartek.agentctl.examples.agentshop.shop.ShopFeed
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val pending = MockOrders.alice.last()

class OrdersListTest {
    @Test
    fun firstAppearanceLoadsOnce() {
        var fetched = 0
        val store = TestStore(OrdersList.State(), reducer = OrdersList.reducer(OrdersClient(fetchOrders = { fetched += 1; MockOrders.alice })))
        assertEquals(OrdersList.Phase.LOADING, store.state.phase)
        store.send(OrdersList.Action.OnAppear).send(OrdersList.Action.OnAppear)
        assertEquals(1, fetched)
        assertEquals(OrdersList.Phase.LOADED, store.state.phase)
        assertTrue(SummaryItem("statuses", "delivered,shipped,pending") in OrdersListAgent.activeScreen(store.state).summary)
    }

    @Test
    fun noOrdersIsEmpty() {
        val store = TestStore(OrdersList.State(), reducer = OrdersList.reducer(OrdersClient(fetchOrders = { emptyList() })))
        store.send(OrdersList.Action.OnAppear)
        assertEquals(OrdersList.Phase.EMPTY, store.state.phase)
        assertTrue(SummaryItem("statuses", "none") in OrdersListAgent.activeScreen(store.state).summary)
    }

    @Test
    fun failureThenRetryAndARefreshKeepsRows() {
        var fail = true
        val client = OrdersClient(fetchOrders = { if (fail) throw OrdersException(OrdersError.NETWORK) else MockOrders.alice })
        val store = TestStore(OrdersList.State(), reducer = OrdersList.reducer(client))
        store.send(OrdersList.Action.OnAppear)
        assertEquals(OrdersList.Phase.FAILED, store.state.phase)
        fail = false
        store.send(OrdersList.Action.Retry)
        assertEquals(OrdersList.State(orders = MockOrders.alice, hasLoaded = true), store.state)
        fail = true
        store.send(OrdersList.Action.Refresh)
        assertEquals(OrdersList.Phase.LOADED, store.state.phase)
        assertEquals(OrdersError.NETWORK, store.state.error)
    }

    @Test
    fun tappingAsksToOpenAndUpdatesReplaceTheRow() {
        val store = TestStore(OrdersList.State(orders = MockOrders.alice, hasLoaded = true), reducer = OrdersList.reducer(OrdersClient()))
        store.send(OrdersList.Action.OrderTapped(1003))
        assertEquals(OrdersList.Action.Delegate.OpenOrder(1003), store.received.last())
        store.send(OrdersList.Action.OrderUpdated(pending.copy(status = OrderStatus.CANCELLED)))
        assertEquals(listOf(OrderStatus.DELIVERED, OrderStatus.SHIPPED, OrderStatus.CANCELLED), store.state.orders.map { it.status })
        val placed = Order(1004, OrderStatus.PENDING, day("2026-01-01"), emptyList())
        assertEquals(1004, store.send(OrdersList.Action.OrderUpdated(placed)).state.orders.last().id)
    }
}

class OrderDetailTest {
    @Test
    fun loadsOnAppearanceAndCancels() {
        val client = OrdersClient(fetchOrder = { pending }, cancelOrder = { pending.copy(status = OrderStatus.CANCELLED) })
        val store = TestStore(OrderDetail.State(orderID = 1003), reducer = OrderDetail.reducer(client))
        assertEquals(
            listOf(SummaryItem("id", 1003), SummaryItem("canCancel", false), SummaryItem("loading", false)),
            OrderDetailAgent.activeScreen(store.state).summary,
        )
        store.send(OrderDetail.Action.OnAppear)
        assertTrue(store.state.canCancel)
        assertEquals(
            listOf("id", "status", "items", "total", "date", "canCancel", "loading"),
            OrderDetailAgent.activeScreen(store.state).summary.map { it.key },
        )
        assertTrue(SummaryItem("date", "2025-12-30") in OrderDetailAgent.activeScreen(store.state).summary)
        store.send(OrderDetail.Action.CancelTapped)
        assertEquals(OrderStatus.CANCELLED, store.state.order?.status)
        assertFalse(store.state.canCancel)
        assertEquals(OrderDetail.Action.Delegate.OrderCancelled(pending.copy(status = OrderStatus.CANCELLED)), store.received.last())
    }

    @Test
    fun loadFailuresThenRetry() {
        for (error in listOf(OrdersError.NOT_FOUND, OrdersError.NETWORK)) {
            var fail = true
            val client = OrdersClient(fetchOrder = { if (fail) throw OrdersException(error) else pending })
            val store = TestStore(OrderDetail.State(orderID = 1003), reducer = OrderDetail.reducer(client))
            store.send(OrderDetail.Action.OnAppear)
            assertEquals(OrderDetail.State(orderID = 1003, error = error), store.state)
            fail = false
            store.send(OrderDetail.Action.Retry)
            assertEquals(OrderDetail.State(orderID = 1003, order = pending), store.state)
        }
    }

    @Test
    fun cancelFailuresAndOnlyPendingOrders() {
        for (error in listOf(OrdersError.NOT_CANCELLABLE, OrdersError.NETWORK)) {
            val store = TestStore(OrderDetail.State(1003, order = pending), reducer = OrderDetail.reducer(OrdersClient(cancelOrder = { throw OrdersException(error) })))
            assertEquals(OrderDetail.State(1003, order = pending, error = error), store.send(OrderDetail.Action.CancelTapped).state)
        }
        assertFalse(OrderDetail.State(1002, order = MockOrders.alice[1]).canCancel)
        assertEquals("canCancel=false", OrderDetailAgent.activeScreen(OrderDetail.State(1002)).command("cancel")?.disabledReason)
    }
}

class ProfileTest {
    private val alice = MockAccounts.alice.user

    @Test
    fun confirmingTheAlertLogsOut() {
        val store = TestStore(Profile.State(alice), reducer = Profile.reducer)
        store.send(Profile.Action.LogoutTapped)
        assertEquals(Profile.Alert.CONFIRM_LOGOUT, store.state.alert)
        store.send(Profile.Action.ConfirmLogoutTapped)
        assertNull(store.state.alert)
        assertEquals(Profile.Action.Delegate.LoggedOut, store.received.last())
    }

    @Test
    fun dismissingTheAlertKeepsTheUserSignedIn() {
        val store = TestStore(Profile.State(alice, Profile.Alert.CONFIRM_LOGOUT), reducer = Profile.reducer)
        store.send(Profile.Action.AlertDismissed)
        assertNull(store.state.alert)
        assertFalse(store.received.any { it is Profile.Action.Delegate })
    }

    @Test
    fun agentCommandsFollowTheAlert() {
        val closed = ProfileAgent.activeScreen(Profile.State(alice))
        assertEquals("alert=none", closed.command("confirm")?.disabledReason)
        assertEquals("alert=none", closed.command("dismiss")?.disabledReason)
        val open = ProfileAgent.activeScreen(Profile.State(alice, Profile.Alert.CONFIRM_LOGOUT))
        assertNull(open.command("confirm")?.disabledReason)
        assertTrue(SummaryItem("alert", "logout") in open.summary)
    }
}

class HomeTabsTest {
    private val session = MockAccounts.session(MockAccounts.alice.user)
    private val home = HomeTabs.State(UUID(0, 0), session)
    private val order = Order(1004, OrderStatus.PENDING, day("2026-01-01"), emptyList())

    private fun store(state: HomeTabs.State = home, ordersClient: OrdersClient = OrdersClient()) = TestStore(
        state,
        reducer = HomeTabs.reducer(CatalogClient(), CartClient(), ordersClient, AccountClient()),
    )

    @Test
    fun switchingTabsAndReselectingPopsToTheFirstScreen() {
        val store = store()
        store.send(HomeTabs.Action.TabSelected(HomeTabs.Tab.ORDERS))
        assertEquals(HomeTabs.Tab.ORDERS, store.state.selectedTab)
        store.send(HomeTabs.Action.OrdersList(OrdersList.Action.Delegate.OpenOrder(1003)))
        store.send(HomeTabs.Action.TabSelected(HomeTabs.Tab.SHOP))
        assertEquals(1, store.state.ordersPath.elements.size, "another tab keeps its stack")
        store.send(HomeTabs.Action.TabSelected(HomeTabs.Tab.ORDERS)).send(HomeTabs.Action.TabSelected(HomeTabs.Tab.ORDERS))
        assertTrue(store.state.ordersPath.isEmpty())
    }

    @Test
    fun openingAnOrderPushesItsDetailAndBackPopsIt() {
        val store = store(home.copy(selectedTab = HomeTabs.Tab.ORDERS))
        store.send(HomeTabs.Action.OrdersList(OrdersList.Action.OrderTapped(1003)))
        assertEquals(listOf(OrderDetail.State(orderID = 1003)), store.state.ordersPath.elements.map { it.screen })
        val screen = HomeTabsAgent.activeScreen(store.state)
        assertEquals("home/orders/1003", screen.path)
        assertEquals(listOf("cancel", "retry", "tab", "back"), screen.commands.map { it.name })
        store.send(screen.command("back")!!.makeAction(null))
        assertTrue(store.state.ordersPath.isEmpty())
    }

    @Test
    fun cancellingInTheDetailUpdatesTheList() {
        val cancelled = pending.copy(status = OrderStatus.CANCELLED)
        val start = home.copy(ordersList = OrdersList.State(orders = MockOrders.alice, hasLoaded = true))
        val store = store(start, OrdersClient(cancelOrder = { cancelled }))
        store.send(HomeTabs.Action.OrdersList(OrdersList.Action.Delegate.OpenOrder(1003)))
        store.send(HomeTabs.Action.OrdersElement(0, OrderDetail.Action.OrderResponse(io.github.olbartek.agentctl.examples.agentshop.models.Outcome.Success(pending))))
        store.send(HomeTabs.Action.OrdersElement(0, OrderDetail.Action.CancelTapped))
        assertEquals(OrderStatus.CANCELLED, store.state.ordersList.orders.last().status)
    }

    @Test
    fun loggingOutIsForwarded() {
        val store = store()
        store.send(HomeTabs.Action.Profile(Profile.Action.Delegate.LoggedOut))
        assertEquals(HomeTabs.Action.Delegate.LoggedOut, store.received.last())
    }

    @Test
    fun addingFromAProductFillsTheCartAndPlacingAnOrderEmptiesIt() {
        val trailRunner = Catalog.products.first()
        val store = store()
        store.send(HomeTabs.Action.Shop(ShopFeed.Action.Delegate.OpenProduct(trailRunner)))
        store.send(HomeTabs.Action.ShopElement(0, ProductDetail.Action.SizeTapped("42")))
        store.send(HomeTabs.Action.ShopElement(0, ProductDetail.Action.AddToCartTapped))
        assertEquals(listOf(CartLine(trailRunner, "42", 1)), store.state.cart.lines)
        store.send(HomeTabs.Action.ShopElement(0, ProductDetail.Action.ViewCartTapped))
        assertEquals(HomeTabs.Tab.CART, store.state.selectedTab)

        store.send(HomeTabs.Action.Cart(Cart.Action.CheckoutTapped))
        assertEquals(listOf<HomeTabs.CartPath>(HomeTabs.CartPath.Checkout(Checkout.State(lines = listOf(CartLine(trailRunner, "42", 1))))), store.state.cartPath.elements.map { it.screen })
        store.send(HomeTabs.Action.CartElement(0, HomeTabs.CartPathAction.Checkout(Checkout.Action.Delegate.Placed(order))))
        assertEquals(HomeTabs.CartPath.Confirmation(OrderConfirmation.State(order)), store.state.cartPath.top?.screen)
        assertEquals(Cart.State(), store.state.cart)
        assertEquals(listOf(order), store.state.ordersList.orders)
        // The order is placed: no going back to checkout.
        val confirmation = HomeTabsAgent.activeScreen(store.state)
        assertEquals("home/cart/confirmation", confirmation.path)
        assertNull(confirmation.command("back"))

        store.send(HomeTabs.Action.CartElement(1, HomeTabs.CartPathAction.Confirmation(OrderConfirmation.Action.ViewOrderTapped)))
        assertTrue(store.state.cartPath.isEmpty())
        assertEquals(HomeTabs.Tab.ORDERS, store.state.selectedTab)
        assertEquals(listOf(OrderDetail.State(orderID = 1004)), store.state.ordersPath.elements.map { it.screen })
    }

    @Test
    fun continueShoppingGoesBackToTheFeed() {
        val store = store()
        store.send(HomeTabs.Action.Shop(ShopFeed.Action.Delegate.OpenProduct(Catalog.products.first())))
        store.send(HomeTabs.Action.TabSelected(HomeTabs.Tab.CART))
        store.send(HomeTabs.Action.Cart(Cart.Action.Add(CartLine(Catalog.products[2], null, 1))))
        store.send(HomeTabs.Action.Cart(Cart.Action.CheckoutTapped))
        store.send(HomeTabs.Action.CartElement(0, HomeTabs.CartPathAction.Checkout(Checkout.Action.Delegate.Placed(order))))
        store.send(HomeTabs.Action.CartElement(1, HomeTabs.CartPathAction.Confirmation(OrderConfirmation.Action.ContinueShoppingTapped)))
        assertEquals(HomeTabs.Tab.SHOP, store.state.selectedTab)
        assertTrue(store.state.cartPath.isEmpty())
        assertTrue(store.state.shopPath.isEmpty())
    }

    @Test
    fun agentScreensAndRegistry() {
        val shop = HomeTabsAgent.activeScreen(home)
        assertEquals("home/shop", shop.path)
        assertEquals("tab", shop.commands.last().name)
        assertEquals("home#00000000-0000-0000-0000-000000000000/home/shop", shop.identity)
        assertEquals(
            listOf(
                "home/shop", "home/shop/<sku>", "home/cart", "home/cart/checkout", "home/cart/confirmation", "home/orders",
                "home/orders/<id>", "home/profile",
            ),
            HomeTabsAgent.registry.map { it.path },
        )
        assertEquals(listOf("tab", "back"), HomeTabsAgent.registry[1].commands.takeLast(2).map { it.name })
    }
}
