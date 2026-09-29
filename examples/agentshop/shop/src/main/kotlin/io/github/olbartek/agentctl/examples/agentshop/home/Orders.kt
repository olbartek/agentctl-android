package io.github.olbartek.agentctl.examples.agentshop.home

import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.CommandGate
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Next
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.examples.agentshop.clients.OrdersClient
import io.github.olbartek.agentctl.examples.agentshop.models.Coded
import io.github.olbartek.agentctl.examples.agentshop.models.Order
import io.github.olbartek.agentctl.examples.agentshop.models.OrdersError
import io.github.olbartek.agentctl.examples.agentshop.models.Outcome
import io.github.olbartek.agentctl.examples.agentshop.models.attempt
import io.github.olbartek.agentctl.examples.agentshop.models.formatCents
import io.github.olbartek.agentctl.examples.agentshop.models.formatDay
import io.github.olbartek.agentctl.invalidArgument
import io.github.olbartek.agentctl.next

/** The signed-in user's orders. Loads on first appearance; supports pull to refresh and retry. */
object OrdersList {
    enum class Phase(override val code: String) : Coded {
        LOADING("loading"),
        LOADED("loaded"),
        EMPTY("empty"),
        FAILED("failed"),
    }

    data class State(
        /** Unique by id. */
        val orders: List<Order> = emptyList(),
        val isLoading: Boolean = false,
        val hasLoaded: Boolean = false,
        val error: OrdersError? = null,
    ) {
        /** What the screen shows. A failed refresh keeps the rows (`loaded`) and shows the error inline. */
        val phase: Phase
            get() = when {
                orders.isNotEmpty() -> Phase.LOADED
                error != null -> Phase.FAILED
                hasLoaded && !isLoading -> Phase.EMPTY
                else -> Phase.LOADING
            }
    }

    sealed interface Action {
        data object OnAppear : Action
        data object Refresh : Action
        data object Retry : Action
        data class OrdersResponse(val result: Outcome<List<Order>, OrdersError>) : Action
        data class OrderTapped(val id: Int) : Action

        /** Sent by the container when another screen changed an order (e.g. it was cancelled). */
        data class OrderUpdated(val order: Order) : Action

        sealed interface Delegate : Action {
            data class OpenOrder(val id: Int) : Delegate
        }
    }

    private object LoadId

    fun reducer(ordersClient: OrdersClient): Reducer<State, Action> {
        fun load(state: State): Next<State, Action> = next(
            state.copy(isLoading = true, error = null),
            Effect.run<Action> { send -> send(Action.OrdersResponse(attempt(OrdersError::of) { ordersClient.fetchOrders() })) }
                .cancellable(LoadId, cancelInFlight = true),
        )

        return Reducer { state, action ->
            when (action) {
                Action.OnAppear -> if (state.hasLoaded || state.isLoading) next(state) else load(state)
                Action.Refresh, Action.Retry -> load(state)
                is Action.OrdersResponse -> when (val result = action.result) {
                    is Outcome.Success ->
                        next(state.copy(isLoading = false, hasLoaded = true, error = null, orders = result.value.distinctBy { it.id }))
                    is Outcome.Failure -> next(state.copy(isLoading = false, hasLoaded = true, error = result.error))
                }
                is Action.OrderTapped -> next(state, Effect.send(Action.Delegate.OpenOrder(action.id)))
                // Replaces the order in place, or appends it if the list did not have it yet.
                is Action.OrderUpdated -> {
                    val orders = if (state.orders.any { it.id == action.order.id }) {
                        state.orders.map { if (it.id == action.order.id) action.order else it }
                    } else {
                        state.orders + action.order
                    }
                    next(state.copy(orders = orders))
                }
                is Action.Delegate -> next(state)
            }
        }
    }
}

object OrdersListAgent : AgentScreen<OrdersList.State, OrdersList.Action> {
    override val screenPaths: List<String> = listOf("home/orders")

    override fun screenPath(state: OrdersList.State): String = "home/orders"

    override val summaryKeys: List<String> = listOf("orders", "loading", "statuses")

    override fun summary(state: OrdersList.State): List<SummaryItem> = listOf(
        SummaryItem("orders", state.orders.size),
        SummaryItem("loading", state.isLoading),
        SummaryItem("statuses", if (state.orders.isEmpty()) "none" else state.orders.joinToString(",") { it.status.code }),
    )

    override fun errorCode(state: OrdersList.State): String? = state.error?.code

    override val onAppear: OrdersList.Action = OrdersList.Action.OnAppear

    override val commands: List<AgentCommand<OrdersList.State, OrdersList.Action>> = listOf(
        AgentCommand.parsing("open", argument = "<id>", help = "Open an order, e.g. open 1003.") { text ->
            OrdersList.Action.OrderTapped(text.toIntOrNull() ?: invalidArgument("expected an order id such as 1003"))
        },
        AgentCommand.action("refresh", help = "Pull to refresh.", action = OrdersList.Action.Refresh),
        AgentCommand.action(
            "retry",
            help = "Retry after a failed load.",
            action = OrdersList.Action.Retry,
            gate = CommandGate("error=none") { it.error != null },
        ),
    )
}

/** One order: items, total, status and date. Pending orders can be cancelled. */
object OrderDetail {
    data class State(
        val orderID: Int,
        val order: Order? = null,
        val isLoading: Boolean = false,
        val isCancelling: Boolean = false,
        val error: OrdersError? = null,
    ) {
        /** Only pending orders can be cancelled, and not while a request is running. */
        val canCancel: Boolean get() = order?.isCancellable == true && !isLoading && !isCancelling
    }

    sealed interface Action {
        data object OnAppear : Action
        data object Retry : Action
        data class OrderResponse(val result: Outcome<Order, OrdersError>) : Action
        data object CancelTapped : Action
        data class CancelResponse(val result: Outcome<Order, OrdersError>) : Action

        sealed interface Delegate : Action {
            data class OrderCancelled(val order: Order) : Delegate
        }
    }

    fun reducer(ordersClient: OrdersClient): Reducer<State, Action> {
        fun load(state: State): Next<State, Action> {
            val id = state.orderID
            return next(
                state.copy(isLoading = true, error = null),
                Effect.run { send -> send(Action.OrderResponse(attempt(OrdersError::of) { ordersClient.fetchOrder(id) })) },
            )
        }

        return Reducer { state, action ->
            when (action) {
                Action.OnAppear -> if (state.order != null || state.isLoading) next(state) else load(state)
                Action.Retry -> load(state)
                is Action.OrderResponse -> when (val result = action.result) {
                    is Outcome.Success -> next(state.copy(isLoading = false, order = result.value))
                    is Outcome.Failure -> next(state.copy(isLoading = false, error = result.error))
                }
                Action.CancelTapped -> {
                    if (!state.canCancel) return@Reducer next(state)
                    val id = state.orderID
                    next(
                        state.copy(isCancelling = true, error = null),
                        Effect.run { send -> send(Action.CancelResponse(attempt(OrdersError::of) { ordersClient.cancelOrder(id) })) },
                    )
                }
                is Action.CancelResponse -> when (val result = action.result) {
                    is Outcome.Success -> next(
                        state.copy(isCancelling = false, order = result.value),
                        Effect.send(Action.Delegate.OrderCancelled(result.value)),
                    )
                    is Outcome.Failure -> next(state.copy(isCancelling = false, error = result.error))
                }
                is Action.Delegate -> next(state)
            }
        }
    }
}

object OrderDetailAgent : AgentScreen<OrderDetail.State, OrderDetail.Action> {
    override val screenPaths: List<String> = listOf("home/orders/<id>")

    override fun screenPath(state: OrderDetail.State): String = "home/orders/${state.orderID}"

    override val summaryKeys: List<String> = listOf("id", "status", "items", "total", "date", "canCancel", "loading")

    override fun summary(state: OrderDetail.State): List<SummaryItem> {
        val items = mutableListOf(SummaryItem("id", state.orderID))
        state.order?.let { order ->
            items += listOf(
                SummaryItem("status", order.status.code),
                SummaryItem("items", order.items.size),
                SummaryItem("total", formatCents(order.totalCents)),
                SummaryItem("date", formatDay(order.placedOn)),
            )
        }
        items += listOf(SummaryItem("canCancel", state.canCancel), SummaryItem("loading", state.isLoading || state.isCancelling))
        return items
    }

    override fun errorCode(state: OrderDetail.State): String? = state.error?.code

    override val onAppear: OrderDetail.Action = OrderDetail.Action.OnAppear

    override val commands: List<AgentCommand<OrderDetail.State, OrderDetail.Action>> = listOf(
        AgentCommand.action(
            "cancel",
            help = "Cancel the order (pending orders only).",
            action = OrderDetail.Action.CancelTapped,
            gate = CommandGate("canCancel=false") { it.canCancel },
        ),
        AgentCommand.action(
            "retry",
            help = "Reload the order after an error.",
            action = OrderDetail.Action.Retry,
            gate = CommandGate("error=none") { it.error != null },
        ),
    )
}
