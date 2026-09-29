package io.github.olbartek.agentctl.examples.agentshop.shop

import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.CommandGate
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.examples.agentshop.clients.CartClient
import io.github.olbartek.agentctl.examples.agentshop.clients.Promo
import io.github.olbartek.agentctl.examples.agentshop.models.CartError
import io.github.olbartek.agentctl.examples.agentshop.models.CartLine
import io.github.olbartek.agentctl.examples.agentshop.models.Outcome
import io.github.olbartek.agentctl.examples.agentshop.models.attempt
import io.github.olbartek.agentctl.examples.agentshop.models.formatCents
import io.github.olbartek.agentctl.examples.agentshop.models.trimmingWhitespaces
import io.github.olbartek.agentctl.next

/** The cart: lines grouped by product and size, quantities, and one promo code. The server checks the code. */
object Cart {
    data class State(
        /** Unique by [CartLine.id], in the order they were first added. */
        val lines: List<CartLine> = emptyList(),
        /** What is typed in the promo field. */
        val promoCode: String = "",
        val promo: Promo? = null,
        val isApplyingPromo: Boolean = false,
        val error: CartError? = null,
    ) {
        val itemCount: Int get() = lines.sumOf { it.quantity }
        val subtotalCents: Int get() = lines.sumOf { it.totalCents }
        val discountCents: Int get() = promo?.discount(subtotalCents) ?: 0
        val totalCents: Int get() = subtotalCents - discountCents
        val canCheckout: Boolean get() = lines.isNotEmpty()
        val canApplyPromo: Boolean get() = promoCode.trimmingWhitespaces().isNotEmpty() && !isApplyingPromo
    }

    sealed interface Action {
        data class PromoCodeChanged(val code: String) : Action

        /** From the product screen, through the tabs. */
        data class Add(val line: CartLine) : Action
        data class IncrementTapped(val id: String) : Action
        data class DecrementTapped(val id: String) : Action
        data class RemoveTapped(val id: String) : Action
        data object ApplyPromoTapped : Action
        data class PromoResponse(val result: Outcome<Promo, CartError>) : Action
        data object ClearPromoTapped : Action
        data object CheckoutTapped : Action

        /** Sent by the tabs once an order is placed. */
        data object Emptied : Action

        sealed interface Delegate : Action {
            data object Checkout : Delegate
        }
    }

    fun reducer(cartClient: CartClient): Reducer<State, Action> = Reducer { state, action ->
        fun update(id: String, change: (CartLine) -> CartLine?): List<CartLine> =
            state.lines.mapNotNull { if (it.id == id) change(it) else it }

        when (action) {
            is Action.PromoCodeChanged -> next(state.copy(promoCode = action.code, error = null))
            is Action.Add ->
                if (state.lines.any { it.id == action.line.id }) {
                    next(state.copy(lines = update(action.line.id) { it.copy(quantity = it.quantity + action.line.quantity) }))
                } else {
                    next(state.copy(lines = state.lines + action.line))
                }
            is Action.IncrementTapped -> next(state.copy(lines = update(action.id) { it.copy(quantity = it.quantity + 1) }))
            is Action.DecrementTapped ->
                next(state.copy(lines = update(action.id) { if (it.quantity > 1) it.copy(quantity = it.quantity - 1) else null }))
            is Action.RemoveTapped -> next(state.copy(lines = update(action.id) { null }))
            Action.ApplyPromoTapped -> {
                if (!state.canApplyPromo) return@Reducer next(state)
                val code = state.promoCode
                next(
                    state.copy(isApplyingPromo = true, error = null),
                    Effect.run { send -> send(Action.PromoResponse(attempt(CartError::of) { cartClient.applyPromo(code) })) },
                )
            }
            is Action.PromoResponse -> when (val result = action.result) {
                is Outcome.Success -> next(state.copy(isApplyingPromo = false, promo = result.value, promoCode = ""))
                is Outcome.Failure -> next(state.copy(isApplyingPromo = false, error = result.error))
            }
            Action.ClearPromoTapped -> next(state.copy(promo = null))
            Action.CheckoutTapped -> if (state.canCheckout) next(state, Effect.send(Action.Delegate.Checkout)) else next(state)
            Action.Emptied -> next(State())
            is Action.Delegate -> next(state)
        }
    }
}

object CartAgent : AgentScreen<Cart.State, Cart.Action> {
    override val screenPaths: List<String> = listOf("home/cart")

    override fun screenPath(state: Cart.State): String = "home/cart"

    override val summaryKeys: List<String> = listOf("lines", "items", "subtotal", "discount", "total", "promo", "canCheckout")

    override fun summary(state: Cart.State): List<SummaryItem> = listOf(
        SummaryItem("lines", if (state.lines.isEmpty()) "none" else state.lines.joinToString(",") { it.id }),
        SummaryItem("items", state.itemCount),
        SummaryItem("subtotal", formatCents(state.subtotalCents)),
        SummaryItem("discount", formatCents(state.discountCents)),
        SummaryItem("total", formatCents(state.totalCents)),
        SummaryItem("promo", state.promo?.code ?: "none"),
        SummaryItem("canCheckout", state.canCheckout),
    )

    override fun errorCode(state: Cart.State): String? = state.error?.code

    override val commands: List<AgentCommand<Cart.State, Cart.Action>> = listOf(
        AgentCommand.parsing("inc", argument = "<line>", help = "One more of a line, e.g. inc 101-42 or inc 103.") {
            Cart.Action.IncrementTapped(it)
        },
        AgentCommand.parsing("dec", argument = "<line>", help = "One fewer of a line; at one, the line is removed.") {
            Cart.Action.DecrementTapped(it)
        },
        AgentCommand.parsing("remove", argument = "<line>", help = "Remove a line.") { Cart.Action.RemoveTapped(it) },
        AgentCommand.text("promo-code", help = "Type in the promo code field (SAVE10 and HALF exist).") { Cart.Action.PromoCodeChanged(it) },
        AgentCommand.action(
            "apply-promo",
            help = "Apply the typed code (cart.applyPromo); an unknown one reports error=invalidPromo.",
            action = Cart.Action.ApplyPromoTapped,
            gate = CommandGate("no code typed") { it.canApplyPromo },
        ),
        AgentCommand.action(
            "clear-promo",
            help = "Remove the applied promo code.",
            action = Cart.Action.ClearPromoTapped,
            gate = CommandGate("promo=none") { it.promo != null },
        ),
        AgentCommand.action(
            "checkout",
            help = "Go to checkout.",
            action = Cart.Action.CheckoutTapped,
            gate = CommandGate("canCheckout=false") { it.canCheckout },
        ),
    )
}
