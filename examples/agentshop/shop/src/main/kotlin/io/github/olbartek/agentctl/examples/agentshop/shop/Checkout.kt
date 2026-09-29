package io.github.olbartek.agentctl.examples.agentshop.shop

import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.CommandGate
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.examples.agentshop.clients.AccountClient
import io.github.olbartek.agentctl.examples.agentshop.clients.OrdersClient
import io.github.olbartek.agentctl.examples.agentshop.models.AccountProfile
import io.github.olbartek.agentctl.examples.agentshop.models.Address
import io.github.olbartek.agentctl.examples.agentshop.models.CartLine
import io.github.olbartek.agentctl.examples.agentshop.models.Coded
import io.github.olbartek.agentctl.examples.agentshop.models.Order
import io.github.olbartek.agentctl.examples.agentshop.models.OrderRequest
import io.github.olbartek.agentctl.examples.agentshop.models.OrdersError
import io.github.olbartek.agentctl.examples.agentshop.models.Outcome
import io.github.olbartek.agentctl.examples.agentshop.models.attempt
import io.github.olbartek.agentctl.examples.agentshop.models.codeChoices
import io.github.olbartek.agentctl.examples.agentshop.models.codeOf
import io.github.olbartek.agentctl.examples.agentshop.models.formatCents
import io.github.olbartek.agentctl.examples.agentshop.models.isValidCardNumber
import io.github.olbartek.agentctl.examples.agentshop.models.isValidZip
import io.github.olbartek.agentctl.invalidArgument
import io.github.olbartek.agentctl.next

/**
 * Checkout: the shipping address (prefilled from the account), shipping speed and payment, then "Place order". Local
 * checks come first — a zip that isn't five digits is `invalidZip`, a card that isn't 16 digits `invalidCard` — then
 * the server may decline the card (`paymentDeclined`) or fail (`network`); placing again retries.
 */
object Checkout {
    const val EXPRESS_SHIPPING_CENTS: Int = 1500

    enum class Shipping(override val code: String) : Coded {
        STANDARD("standard"),
        EXPRESS("express"),
    }

    enum class Payment(override val code: String) : Coded {
        CARD("card"),
        APPLE_PAY("apple-pay"),
    }

    /** The form's own checks, then the server's. */
    enum class Error(override val code: String) : Coded {
        INVALID_ZIP("invalidZip"),
        INVALID_CARD("invalidCard"),
        PAYMENT_DECLINED("paymentDeclined"),
        NETWORK("network"),
    }

    data class State(
        val lines: List<CartLine>,
        val discountCents: Int = 0,
        val address: Address = Address(),
        val shipping: Shipping = Shipping.STANDARD,
        val payment: Payment = Payment.CARD,
        val cardNumber: String = "",
        val isPlacing: Boolean = false,
        val hasLoadedAddress: Boolean = false,
        val error: Error? = null,
    ) {
        val subtotalCents: Int get() = lines.sumOf { it.totalCents }
        val shippingCents: Int get() = if (shipping == Shipping.EXPRESS) EXPRESS_SHIPPING_CENTS else 0
        val totalCents: Int get() = subtotalCents - discountCents + shippingCents
        val canPlace: Boolean get() = address.isComplete && (payment == Payment.APPLE_PAY || cardNumber.isNotEmpty()) && !isPlacing
    }

    sealed interface Action {
        data class NameChanged(val name: String) : Action
        data class StreetChanged(val street: String) : Action
        data class CityChanged(val city: String) : Action
        data class ZipChanged(val zip: String) : Action
        data class CardNumberChanged(val number: String) : Action
        data object OnAppear : Action
        data class ProfileLoaded(val profile: AccountProfile?) : Action
        data class ShippingTapped(val shipping: Shipping) : Action
        data class PaymentTapped(val payment: Payment) : Action
        data object PlaceOrderTapped : Action
        data class Placed(val result: Outcome<Order, OrdersError>) : Action

        sealed interface Delegate : Action {
            data class Placed(val order: Order) : Delegate
        }
    }

    fun reducer(accountClient: AccountClient, ordersClient: OrdersClient): Reducer<State, Action> = Reducer { state, action ->
        when (action) {
            is Action.NameChanged -> next(state.copy(address = state.address.copy(name = action.name), error = null))
            is Action.StreetChanged -> next(state.copy(address = state.address.copy(street = action.street), error = null))
            is Action.CityChanged -> next(state.copy(address = state.address.copy(city = action.city), error = null))
            is Action.ZipChanged -> next(state.copy(address = state.address.copy(zip = action.zip), error = null))
            is Action.CardNumberChanged -> next(state.copy(cardNumber = action.number, error = null))

            Action.OnAppear -> {
                if (state.hasLoadedAddress) return@Reducer next(state)
                next(
                    state.copy(hasLoadedAddress = true),
                    Effect.run { send ->
                        val profile = attempt({ it }) { accountClient.fetchProfile() }
                        send(Action.ProfileLoaded((profile as? Outcome.Success)?.value))
                    },
                )
            }

            // Prefill only an untouched form: never overwrite what the shopper typed.
            is Action.ProfileLoaded -> {
                val address = action.profile?.address
                if (address != null && state.address == Address()) next(state.copy(address = address)) else next(state)
            }

            is Action.ShippingTapped -> next(state.copy(shipping = action.shipping))
            is Action.PaymentTapped -> next(state.copy(payment = action.payment, error = null))

            Action.PlaceOrderTapped -> {
                if (!state.canPlace) return@Reducer next(state)
                if (!isValidZip(state.address.zip)) return@Reducer next(state.copy(error = Error.INVALID_ZIP))
                if (state.payment == Payment.CARD && !isValidCardNumber(state.cardNumber)) {
                    return@Reducer next(state.copy(error = Error.INVALID_CARD))
                }
                val request = OrderRequest(
                    lines = state.lines,
                    address = state.address,
                    shippingCents = state.shippingCents,
                    discountCents = state.discountCents,
                    cardNumber = if (state.payment == Payment.CARD) state.cardNumber else null,
                )
                next(
                    state.copy(isPlacing = true, error = null),
                    Effect.run { send -> send(Action.Placed(attempt(OrdersError::of) { ordersClient.placeOrder(request) })) },
                )
            }

            is Action.Placed -> when (val result = action.result) {
                is Outcome.Success -> next(state.copy(isPlacing = false), Effect.send(Action.Delegate.Placed(result.value)))
                is Outcome.Failure -> next(
                    state.copy(
                        isPlacing = false,
                        error = if (result.error == OrdersError.PAYMENT_DECLINED) Error.PAYMENT_DECLINED else Error.NETWORK,
                    ),
                )
            }

            is Action.Delegate -> next(state)
        }
    }
}

object CheckoutAgent : AgentScreen<Checkout.State, Checkout.Action> {
    private val shippings = codeChoices<Checkout.Shipping>()
    private val payments = codeChoices<Checkout.Payment>()

    override val screenPaths: List<String> = listOf("home/cart/checkout")

    override fun screenPath(state: Checkout.State): String = "home/cart/checkout"

    override val summaryKeys: List<String> =
        listOf("name", "street", "city", "zip", "shipping", "payment", "total", "canPlace", "loading")

    override fun summary(state: Checkout.State): List<SummaryItem> = listOf(
        SummaryItem("name", state.address.name),
        SummaryItem("street", state.address.street),
        SummaryItem("city", state.address.city),
        SummaryItem("zip", state.address.zip),
        SummaryItem("shipping", state.shipping.code),
        SummaryItem("payment", state.payment.code),
        SummaryItem("total", formatCents(state.totalCents)),
        SummaryItem("canPlace", state.canPlace),
        SummaryItem("loading", state.isPlacing),
    )

    override fun errorCode(state: Checkout.State): String? = state.error?.code

    /** Sent when checkout appears: it loads the saved address (`account.fetchProfile`). */
    override val onAppear: Checkout.Action = Checkout.Action.OnAppear

    override val commands: List<AgentCommand<Checkout.State, Checkout.Action>> = listOf(
        AgentCommand.text("name", help = "Set the full name.") { Checkout.Action.NameChanged(it) },
        AgentCommand.text("street", help = "Set the street.") { Checkout.Action.StreetChanged(it) },
        AgentCommand.text("city", help = "Set the city.") { Checkout.Action.CityChanged(it) },
        AgentCommand.text("zip", help = "Set the zip code (five digits).") { Checkout.Action.ZipChanged(it) },
        AgentCommand.parsing(
            "shipping",
            argument = "<$shippings>",
            help = "Standard is free; express adds ${formatCents(Checkout.EXPRESS_SHIPPING_CENTS)}.",
        ) { text ->
            Checkout.Action.ShippingTapped(codeOf<Checkout.Shipping>(text) ?: invalidArgument("expected $shippings"))
        },
        AgentCommand.parsing("payment", argument = "<$payments>", help = "Pay by card or with Apple Pay.") { text ->
            Checkout.Action.PaymentTapped(codeOf<Checkout.Payment>(text) ?: invalidArgument("expected $payments"))
        },
        AgentCommand.text(
            "card",
            help = "Type the card number (16 digits; 4000 0000 0000 0002 is declined).",
            argument = "<number>",
            gate = CommandGate("payment=apple-pay") { it.payment == Checkout.Payment.CARD },
        ) { Checkout.Action.CardNumberChanged(it) },
        AgentCommand.action(
            "place-order",
            help = "Place the order (orders.placeOrder). Reports invalidZip, invalidCard, paymentDeclined or network.",
            action = Checkout.Action.PlaceOrderTapped,
            gate = CommandGate("canPlace=false") { it.canPlace },
        ),
    )
}

/** "Thanks for your order": the new order's number and total, then on to the order or back to the shop. */
object OrderConfirmation {
    data class State(val order: Order)

    sealed interface Action {
        data object ViewOrderTapped : Action
        data object ContinueShoppingTapped : Action

        sealed interface Delegate : Action {
            data class ViewOrder(val id: Int) : Delegate
            data object ContinueShopping : Delegate
        }
    }

    val reducer: Reducer<State, Action> = Reducer { state, action ->
        when (action) {
            Action.ViewOrderTapped -> next(state, Effect.send(Action.Delegate.ViewOrder(state.order.id)))
            Action.ContinueShoppingTapped -> next(state, Effect.send(Action.Delegate.ContinueShopping))
            is Action.Delegate -> next(state)
        }
    }
}

object OrderConfirmationAgent : AgentScreen<OrderConfirmation.State, OrderConfirmation.Action> {
    override val screenPaths: List<String> = listOf("home/cart/confirmation")

    override fun screenPath(state: OrderConfirmation.State): String = "home/cart/confirmation"

    override val summaryKeys: List<String> = listOf("order", "total")

    override fun summary(state: OrderConfirmation.State): List<SummaryItem> =
        listOf(SummaryItem("order", state.order.id), SummaryItem("total", formatCents(state.order.totalCents)))

    override val commands: List<AgentCommand<OrderConfirmation.State, OrderConfirmation.Action>> = listOf(
        AgentCommand.action("view-order", help = "Open the new order in the orders tab.", action = OrderConfirmation.Action.ViewOrderTapped),
        AgentCommand.action(
            "continue-shopping",
            help = "Back to the shop, with an empty cart.",
            action = OrderConfirmation.Action.ContinueShoppingTapped,
        ),
    )
}
