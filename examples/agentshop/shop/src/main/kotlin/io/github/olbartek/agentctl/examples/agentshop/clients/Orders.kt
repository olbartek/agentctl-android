package io.github.olbartek.agentctl.examples.agentshop.clients

import io.github.olbartek.agentctl.AgentClock
import io.github.olbartek.agentctl.MockMethod
import io.github.olbartek.agentctl.examples.agentshop.models.Order
import io.github.olbartek.agentctl.examples.agentshop.models.OrderItem
import io.github.olbartek.agentctl.examples.agentshop.models.OrderRequest
import io.github.olbartek.agentctl.examples.agentshop.models.OrderStatus
import io.github.olbartek.agentctl.examples.agentshop.models.OrdersError
import io.github.olbartek.agentctl.examples.agentshop.models.OrdersException
import io.github.olbartek.agentctl.examples.agentshop.models.ShopCalls
import io.github.olbartek.agentctl.examples.agentshop.models.codeOf
import io.github.olbartek.agentctl.examples.agentshop.models.day
import java.time.Instant

/** The in-memory orders server behind [OrdersClient.live]. Orders are keyed by the owner's email. */
class OrdersBackend(seed: Map<String, List<Order>> = MockOrders.seed) {
    private val lock = Any()
    private val ordersByEmail = seed.toMutableMap()

    /** The user's orders, newest id last. */
    fun orders(email: String): List<Order> = synchronized(lock) { (ordersByEmail[email] ?: emptyList()).sortedBy { it.id } }

    fun order(id: Int, email: String): Order = synchronized(lock) {
        (ordersByEmail[email] ?: emptyList()).firstOrNull { it.id == id } ?: throw OrdersException(OrdersError.NOT_FOUND)
    }

    /**
     * Places an order: the next id after the user's newest (1001 for a first order), pending, dated `date`. Shipping
     * and a promo discount are line items, so the order's total is what checkout showed.
     */
    fun placeOrder(request: OrderRequest, email: String, date: Instant): Order = synchronized(lock) {
        if (request.cardNumber?.replace(" ", "") == DECLINED_CARD) throw OrdersException(OrdersError.PAYMENT_DECLINED)
        val items = request.lines.map { line ->
            OrderItem(
                name = if (line.size != null) "${line.product.name} (${line.size})" else line.product.name,
                quantity = line.quantity,
                unitPriceCents = line.product.priceCents,
            )
        }.toMutableList()
        if (request.shippingCents > 0) items.add(OrderItem("Express shipping", 1, request.shippingCents))
        if (request.discountCents > 0) items.add(OrderItem("Promo discount", 1, -request.discountCents))
        val orders = ordersByEmail[email] ?: emptyList()
        val order = Order((orders.maxOfOrNull { it.id } ?: 1000) + 1, OrderStatus.PENDING, date, items)
        ordersByEmail[email] = orders + order
        order
    }

    /** Cancels a pending order; any other status gives `notCancellable`. */
    fun cancelOrder(id: Int, email: String): Order = synchronized(lock) {
        val orders = ordersByEmail[email] ?: throw OrdersException(OrdersError.NOT_FOUND)
        val index = orders.indexOfFirst { it.id == id }
        if (index < 0) throw OrdersException(OrdersError.NOT_FOUND)
        if (!orders[index].isCancellable) throw OrdersException(OrdersError.NOT_CANCELLABLE)
        val cancelled = orders[index].copy(status = OrderStatus.CANCELLED)
        ordersByEmail[email] = orders.toMutableList().also { it[index] = cancelled }
        cancelled
    }

    companion object {
        /** The card the mock always declines. */
        const val DECLINED_CARD: String = "4000000000000002"
    }
}

/** Seeded orders: Alice has three (delivered, shipped, pending); Bob has none. */
object MockOrders {
    val alice: List<Order> = listOf(
        Order(
            1001,
            OrderStatus.DELIVERED,
            day("2025-12-10"),
            listOf(OrderItem("Wireless Mouse", 1, 2499), OrderItem("USB-C Cable", 2, 950)),
        ),
        Order(1002, OrderStatus.SHIPPED, day("2025-12-22"), listOf(OrderItem("Mechanical Keyboard", 1, 8900))),
        Order(
            1003,
            OrderStatus.PENDING,
            day("2025-12-30"),
            listOf(OrderItem("Laptop Stand", 1, 3990), OrderItem("Desk Mat", 1, 1990)),
        ),
    )

    val seed: Map<String, List<Order>> = mapOf("alice@example.com" to alice)
}

/**
 * Orders of the signed-in user. Everything is mocked: [live] talks to the in-memory [OrdersBackend] and identifies
 * the user from [SessionStorage], the way a server reads a token.
 */
class OrdersClient(
    val fetchOrders: suspend () -> List<Order> = { unimplemented("orders.fetchOrders") },
    val fetchOrder: suspend (id: Int) -> Order = { unimplemented("orders.fetchOrder") },
    val cancelOrder: suspend (id: Int) -> Order = { unimplemented("orders.cancelOrder") },
    val placeOrder: suspend (request: OrderRequest) -> Order = { unimplemented("orders.placeOrder") },
) {
    companion object {
        /** @param clock dates a placed order: its `now()`, which is fixed headlessly. */
        fun live(calls: ShopCalls, storage: SessionStorage, backend: OrdersBackend, clock: AgentClock): OrdersClient {
            suspend fun <T> call(name: String, body: (email: String) -> T): T =
                calls.call(name, { code -> OrdersException(codeOf<OrdersError>(code) ?: OrdersError.NETWORK) }) {
                    val email = storage.currentSession?.user?.email ?: throw OrdersException(OrdersError.UNAUTHORIZED)
                    body(email)
                }
            return OrdersClient(
                fetchOrders = { call("orders.fetchOrders") { email -> backend.orders(email) } },
                fetchOrder = { id -> call("orders.fetchOrder") { email -> backend.order(id, email) } },
                cancelOrder = { id -> call("orders.cancelOrder") { email -> backend.cancelOrder(id, email) } },
                placeOrder = { request ->
                    val now = clock.now()
                    call("orders.placeOrder") { email -> backend.placeOrder(request, email, now) }
                },
            )
        }

        /** The methods `mock orders.<method> <error>` accepts, with their error codes. */
        val mockMethods: List<MockMethod> = listOf("fetchOrders", "fetchOrder", "cancelOrder", "placeOrder").map {
            MockMethod("orders.$it", OrdersError.entries.map { error -> error.code })
        }
    }
}
