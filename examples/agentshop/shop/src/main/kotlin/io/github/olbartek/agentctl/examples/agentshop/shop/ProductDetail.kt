package io.github.olbartek.agentctl.examples.agentshop.shop

import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.CommandGate
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.examples.agentshop.models.CartLine
import io.github.olbartek.agentctl.examples.agentshop.models.Coded
import io.github.olbartek.agentctl.examples.agentshop.models.Product
import io.github.olbartek.agentctl.examples.agentshop.models.formatCents
import io.github.olbartek.agentctl.next

/**
 * One product: pick a size (when it has sizes) and a quantity (1–5), then add it to the cart. An out-of-stock product
 * can't be added. The cart itself lives in the tabs; this screen asks for the add through a delegate.
 */
object ProductDetail {
    const val MAX_QUANTITY: Int = 5

    enum class Error(override val code: String) : Coded {
        MAX_QUANTITY("maxQuantity"),
        MIN_QUANTITY("minQuantity"),
    }

    data class State(
        val product: Product,
        val size: String? = null,
        val quantity: Int = 1,
        val isFavorite: Boolean = false,
        /** How many this screen has put in the cart so far. */
        val added: Int = 0,
        val error: Error? = null,
    ) {
        val canAdd: Boolean get() = product.inStock && (product.sizes.isEmpty() || size != null)
    }

    sealed interface Action {
        data class SizeTapped(val size: String) : Action
        data object QuantityUpTapped : Action
        data object QuantityDownTapped : Action
        data class FavoriteToggled(val isOn: Boolean) : Action
        data object AddToCartTapped : Action
        data object ViewCartTapped : Action

        sealed interface Delegate : Action {
            data class Add(val line: CartLine) : Delegate
            data object ViewCart : Delegate
        }
    }

    val reducer: Reducer<State, Action> = Reducer { state, action ->
        when (action) {
            is Action.SizeTapped -> if (action.size in state.product.sizes) next(state.copy(size = action.size)) else next(state)
            Action.QuantityUpTapped ->
                if (state.quantity < MAX_QUANTITY) {
                    next(state.copy(quantity = state.quantity + 1, error = null))
                } else {
                    next(state.copy(error = Error.MAX_QUANTITY))
                }
            Action.QuantityDownTapped ->
                if (state.quantity > 1) next(state.copy(quantity = state.quantity - 1, error = null)) else next(state.copy(error = Error.MIN_QUANTITY))
            is Action.FavoriteToggled -> next(state.copy(isFavorite = action.isOn))
            Action.AddToCartTapped -> {
                if (!state.canAdd) return@Reducer next(state)
                val line = CartLine(state.product, state.size, state.quantity)
                next(state.copy(added = state.added + state.quantity, error = null), Effect.send(Action.Delegate.Add(line)))
            }
            Action.ViewCartTapped -> next(state, Effect.send(Action.Delegate.ViewCart))
            is Action.Delegate -> next(state)
        }
    }
}

object ProductDetailAgent : AgentScreen<ProductDetail.State, ProductDetail.Action> {
    override val screenPaths: List<String> = listOf("home/shop/<sku>")

    override fun screenPath(state: ProductDetail.State): String = "home/shop/${state.product.id}"

    override val summaryKeys: List<String> = listOf("name", "price", "size", "qty", "inStock", "favorite", "canAdd", "added")

    override fun summary(state: ProductDetail.State): List<SummaryItem> = listOf(
        SummaryItem("name", state.product.name),
        SummaryItem("price", formatCents(state.product.priceCents)),
        SummaryItem("size", if (state.product.sizes.isEmpty()) "one-size" else state.size ?: "none"),
        SummaryItem("qty", state.quantity),
        SummaryItem("inStock", state.product.inStock),
        SummaryItem("favorite", state.isFavorite),
        SummaryItem("canAdd", state.canAdd),
        SummaryItem("added", state.added),
    )

    override fun errorCode(state: ProductDetail.State): String? = state.error?.code

    override val commands: List<AgentCommand<ProductDetail.State, ProductDetail.Action>> = listOf(
        AgentCommand.text("size", help = "Pick a size, e.g. size 42 or size M.", argument = "<size>") { ProductDetail.Action.SizeTapped(it) },
        AgentCommand.action(
            "qty-up",
            help = "One more (at most ${ProductDetail.MAX_QUANTITY}; beyond that error=maxQuantity).",
            action = ProductDetail.Action.QuantityUpTapped,
        ),
        AgentCommand.action(
            "qty-down",
            help = "One fewer (at least 1; below that error=minQuantity).",
            action = ProductDetail.Action.QuantityDownTapped,
        ),
        AgentCommand.onOff("favorite", help = "Mark or unmark as a favorite.") { ProductDetail.Action.FavoriteToggled(it) },
        AgentCommand.action(
            "add-to-cart",
            help = "Add the quantity in the chosen size to the cart. Needs a size when the product has sizes.",
            action = ProductDetail.Action.AddToCartTapped,
            gate = CommandGate("canAdd=false") { it.canAdd },
        ),
        AgentCommand.action("view-cart", help = "Switch to the cart tab.", action = ProductDetail.Action.ViewCartTapped),
    )
}
