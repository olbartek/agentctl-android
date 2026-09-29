package io.github.olbartek.agentctl.examples.agentshop.home

import io.github.olbartek.agentctl.ActiveScreen
import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentContainer
import io.github.olbartek.agentctl.CommandDoc
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Next
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.ResolvedCommand
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.appending
import io.github.olbartek.agentctl.examples.agentshop.clients.AccountClient
import io.github.olbartek.agentctl.examples.agentshop.clients.CartClient
import io.github.olbartek.agentctl.examples.agentshop.clients.CatalogClient
import io.github.olbartek.agentctl.examples.agentshop.clients.OrdersClient
import io.github.olbartek.agentctl.examples.agentshop.models.Coded
import io.github.olbartek.agentctl.examples.agentshop.models.Session
import io.github.olbartek.agentctl.examples.agentshop.models.codeChoices
import io.github.olbartek.agentctl.examples.agentshop.models.codeOf
import io.github.olbartek.agentctl.examples.agentshop.navigation.Stack
import io.github.olbartek.agentctl.examples.agentshop.shop.Cart
import io.github.olbartek.agentctl.examples.agentshop.shop.CartAgent
import io.github.olbartek.agentctl.examples.agentshop.shop.Checkout
import io.github.olbartek.agentctl.examples.agentshop.shop.CheckoutAgent
import io.github.olbartek.agentctl.examples.agentshop.shop.OrderConfirmation
import io.github.olbartek.agentctl.examples.agentshop.shop.OrderConfirmationAgent
import io.github.olbartek.agentctl.examples.agentshop.shop.ProductDetail
import io.github.olbartek.agentctl.examples.agentshop.shop.ProductDetailAgent
import io.github.olbartek.agentctl.examples.agentshop.shop.ShopFeed
import io.github.olbartek.agentctl.examples.agentshop.shop.ShopFeedAgent
import io.github.olbartek.agentctl.invalidArgument
import io.github.olbartek.agentctl.next
import java.util.UUID

/**
 * The signed-in area, four tabs:
 * - shop: the feed, with a product pushed on top;
 * - cart: the cart, with checkout and then the order confirmation pushed on top;
 * - orders: the list, with an order's detail pushed on top;
 * - profile.
 *
 * The cart lives here rather than in its tab, because the product screen in the shop tab adds to it.
 */
object HomeTabs {
    enum class Tab(override val code: String) : Coded {
        SHOP("shop"),
        CART("cart"),
        ORDERS("orders"),
        PROFILE("profile"),
    }

    /** A screen pushed in the cart tab. */
    sealed interface CartPath {
        data class Checkout(val state: io.github.olbartek.agentctl.examples.agentshop.shop.Checkout.State) : CartPath
        data class Confirmation(val state: OrderConfirmation.State) : CartPath
    }

    /** An action for a screen pushed in the cart tab, matching its [CartPath]. */
    sealed interface CartPathAction {
        data class Checkout(val action: io.github.olbartek.agentctl.examples.agentshop.shop.Checkout.Action) : CartPathAction
        data class Confirmation(val action: OrderConfirmation.Action) : CartPathAction
    }

    data class State(
        /**
         * Identifies this signed-in session's home. A new id means a fresh home, so the UI (and the headless runtime)
         * treats its screens as newly appeared.
         */
        val id: UUID,
        val selectedTab: Tab = Tab.SHOP,
        val shop: ShopFeed.State = ShopFeed.State(),
        /** Products pushed above the feed. */
        val shopPath: Stack<ProductDetail.State> = Stack("shop"),
        val cart: Cart.State = Cart.State(),
        val cartPath: Stack<CartPath> = Stack("cart"),
        val ordersList: OrdersList.State = OrdersList.State(),
        /** Orders pushed above the list. */
        val ordersPath: Stack<OrderDetail.State> = Stack("orders"),
        val profile: Profile.State,
    ) {
        constructor(id: UUID, session: Session) : this(id = id, profile = Profile.State(session.user))
    }

    sealed interface Action {
        data class TabSelected(val tab: Tab) : Action
        data class Shop(val action: ShopFeed.Action) : Action
        data class ShopElement(val id: Int, val action: ProductDetail.Action) : Action
        data class ShopPopFrom(val id: Int) : Action
        data class Cart(val action: io.github.olbartek.agentctl.examples.agentshop.shop.Cart.Action) : Action
        data class CartElement(val id: Int, val action: CartPathAction) : Action
        data class CartPopFrom(val id: Int) : Action
        data class OrdersList(val action: io.github.olbartek.agentctl.examples.agentshop.home.OrdersList.Action) : Action
        data class OrdersElement(val id: Int, val action: OrderDetail.Action) : Action
        data class OrdersPopFrom(val id: Int) : Action
        data class Profile(val action: io.github.olbartek.agentctl.examples.agentshop.home.Profile.Action) : Action

        sealed interface Delegate : Action {
            data object LoggedOut : Delegate
        }
    }

    fun reducer(
        catalogClient: CatalogClient,
        cartClient: CartClient,
        ordersClient: OrdersClient,
        accountClient: AccountClient,
    ): Reducer<State, Action> {
        val shop = ShopFeed.reducer(catalogClient)
        val cart = Cart.reducer(cartClient)
        val ordersList = OrdersList.reducer(ordersClient)
        val checkout = Checkout.reducer(accountClient, ordersClient)
        val orderDetail = OrderDetail.reducer(ordersClient)

        fun reduceCartScreen(screen: CartPath, action: CartPathAction): Next<CartPath, CartPathAction>? = when {
            screen is CartPath.Checkout && action is CartPathAction.Checkout -> checkout.reduce(screen.state, action.action)
                .let { Next(CartPath.Checkout(it.state), it.effect.map { a -> CartPathAction.Checkout(a) }) }
            screen is CartPath.Confirmation && action is CartPathAction.Confirmation ->
                OrderConfirmation.reducer.reduce(screen.state, action.action)
                    .let { Next(CartPath.Confirmation(it.state), it.effect.map { a -> CartPathAction.Confirmation(a) }) }
            else -> null
        }

        /** The child screens' reducers: the tabs' roots and the stack elements. */
        val children = Reducer<State, Action> { state, action ->
            when (action) {
                is Action.Shop -> shop.reduce(state.shop, action.action)
                    .let { Next(state.copy(shop = it.state), it.effect.map { a -> Action.Shop(a) }) }
                is Action.Cart -> cart.reduce(state.cart, action.action)
                    .let { Next(state.copy(cart = it.state), it.effect.map { a -> Action.Cart(a) }) }
                is Action.OrdersList -> ordersList.reduce(state.ordersList, action.action)
                    .let { Next(state.copy(ordersList = it.state), it.effect.map { a -> Action.OrdersList(a) }) }
                is Action.Profile -> Profile.reducer.reduce(state.profile, action.action)
                    .let { Next(state.copy(profile = it.state), it.effect.map { a -> Action.Profile(a) }) }
                is Action.ShopElement -> state.shopPath
                    .reduceElement(action.id, { Action.ShopElement(action.id, it) }) { ProductDetail.reducer.reduce(it, action.action) }
                    .let { Next(state.copy(shopPath = it.state), it.effect) }
                is Action.CartElement -> state.cartPath
                    .reduceElement(action.id, { Action.CartElement(action.id, it) }) { reduceCartScreen(it, action.action) }
                    .let { Next(state.copy(cartPath = it.state), it.effect) }
                is Action.OrdersElement -> state.ordersPath
                    .reduceElement(action.id, { Action.OrdersElement(action.id, it) }) { orderDetail.reduce(it, action.action) }
                    .let { Next(state.copy(ordersPath = it.state), it.effect) }
                else -> next(state)
            }
        }

        val tabs = Reducer<State, Action> { state, action ->
            when (action) {
                is Action.TabSelected -> {
                    // As on iOS: selecting the tab that is already selected pops it back to its first screen.
                    val popped: Next<State, Action> = if (action.tab != state.selectedTab) {
                        next(state)
                    } else {
                        when (action.tab) {
                            Tab.SHOP -> state.shopPath.removeAll().let { Next(state.copy(shopPath = it.state), it.effect) }
                            Tab.CART -> state.cartPath.removeAll().let { Next(state.copy(cartPath = it.state), it.effect) }
                            Tab.ORDERS -> state.ordersPath.removeAll().let { Next(state.copy(ordersPath = it.state), it.effect) }
                            Tab.PROFILE -> next(state)
                        }
                    }
                    next(popped.state.copy(selectedTab = action.tab), popped.effect)
                }

                is Action.Shop -> when (val child = action.action) {
                    is ShopFeed.Action.Delegate.OpenProduct ->
                        next(state.copy(shopPath = state.shopPath.push(ProductDetail.State(child.product))))
                    else -> next(state)
                }

                is Action.ShopElement -> when (val child = action.action) {
                    is ProductDetail.Action.Delegate.Add -> next(state, Effect.send(Action.Cart(Cart.Action.Add(child.line))))
                    ProductDetail.Action.Delegate.ViewCart -> next(state.copy(selectedTab = Tab.CART))
                    else -> next(state)
                }

                is Action.Cart -> when (action.action) {
                    Cart.Action.Delegate.Checkout -> next(
                        state.copy(
                            cartPath = state.cartPath.push(
                                CartPath.Checkout(Checkout.State(lines = state.cart.lines, discountCents = state.cart.discountCents)),
                            ),
                        ),
                    )
                    else -> next(state)
                }

                is Action.CartElement -> when (val child = action.action) {
                    is CartPathAction.Checkout -> when (val checkoutAction = child.action) {
                        is Checkout.Action.Delegate.Placed -> next(
                            state.copy(cartPath = state.cartPath.push(CartPath.Confirmation(OrderConfirmation.State(checkoutAction.order)))),
                            Effect.merge(
                                Effect.send(Action.Cart(Cart.Action.Emptied)),
                                Effect.send(Action.OrdersList(OrdersList.Action.OrderUpdated(checkoutAction.order))),
                            ),
                        )
                        else -> next(state)
                    }
                    is CartPathAction.Confirmation -> when (val confirmationAction = child.action) {
                        is OrderConfirmation.Action.Delegate.ViewOrder -> {
                            val cartPopped = state.cartPath.removeAll()
                            val ordersPopped = state.ordersPath.removeAll()
                            next(
                                state.copy(
                                    cartPath = cartPopped.state,
                                    selectedTab = Tab.ORDERS,
                                    ordersPath = ordersPopped.state.push(OrderDetail.State(orderID = confirmationAction.id)),
                                ),
                                Effect.merge(cartPopped.effect, ordersPopped.effect),
                            )
                        }
                        OrderConfirmation.Action.Delegate.ContinueShopping -> {
                            val cartPopped = state.cartPath.removeAll()
                            val shopPopped = state.shopPath.removeAll()
                            next(
                                state.copy(cartPath = cartPopped.state, shopPath = shopPopped.state, selectedTab = Tab.SHOP),
                                Effect.merge(cartPopped.effect, shopPopped.effect),
                            )
                        }
                        else -> next(state)
                    }
                }

                is Action.OrdersList -> when (val child = action.action) {
                    is OrdersList.Action.Delegate.OpenOrder ->
                        next(state.copy(ordersPath = state.ordersPath.push(OrderDetail.State(orderID = child.id))))
                    else -> next(state)
                }

                is Action.OrdersElement -> when (val child = action.action) {
                    is OrderDetail.Action.Delegate.OrderCancelled ->
                        next(state, Effect.send(Action.OrdersList(OrdersList.Action.OrderUpdated(child.order))))
                    else -> next(state)
                }

                is Action.Profile ->
                    if (action.action == Profile.Action.Delegate.LoggedOut) next(state, Effect.send(Action.Delegate.LoggedOut)) else next(state)

                is Action.ShopPopFrom -> state.shopPath.popFrom(action.id).let { Next(state.copy(shopPath = it.state), it.effect) }
                is Action.CartPopFrom -> state.cartPath.popFrom(action.id).let { Next(state.copy(cartPath = it.state), it.effect) }
                is Action.OrdersPopFrom -> state.ordersPath.popFrom(action.id).let { Next(state.copy(ordersPath = it.state), it.effect) }
                is Action.Delegate -> next(state)
            }
        }

        // The children first, then the tabs: a screen handles its own action before its container reads the delegate.
        return io.github.olbartek.agentctl.combine(children, tabs)
    }
}

object HomeTabsAgent : AgentContainer<HomeTabs.State, HomeTabs.Action> {
    private const val BACK_HELP = "Go back to the previous screen."
    private const val TAB_HELP = "Switch tab."
    private val tabs = codeChoices<HomeTabs.Tab>()

    private val tabCommand = AgentCommand.parsing<HomeTabs.State, HomeTabs.Action>("tab", argument = "<$tabs>", help = TAB_HELP) { text ->
        HomeTabs.Action.TabSelected(codeOf<HomeTabs.Tab>(text) ?: invalidArgument("expected $tabs"))
    }

    override fun activeScreen(state: HomeTabs.State): ActiveScreen<HomeTabs.Action> {
        val commands = mutableListOf<ResolvedCommand<HomeTabs.Action>>(tabCommand.resolve(state, source = "HomeTabs"))
        fun back(action: HomeTabs.Action) {
            commands.add(AgentCommand.action<HomeTabs.State, HomeTabs.Action>("back", help = BACK_HELP, action = action).resolve(state, "HomeTabs"))
        }

        val screen: ActiveScreen<HomeTabs.Action> = when (state.selectedTab) {
            HomeTabs.Tab.SHOP -> {
                val top = state.shopPath.top
                if (top != null) {
                    back(HomeTabs.Action.ShopPopFrom(top.id))
                    ProductDetailAgent.activeScreen(top.screen).map { HomeTabs.Action.ShopElement(top.id, it) }.identified("#${top.id}")
                } else {
                    ShopFeedAgent.activeScreen(state.shop).map { HomeTabs.Action.Shop(it) }
                }
            }
            HomeTabs.Tab.CART -> {
                val top = state.cartPath.top
                if (top == null) {
                    CartAgent.activeScreen(state.cart).map { HomeTabs.Action.Cart(it) }
                } else {
                    when (val pushed = top.screen) {
                        is HomeTabs.CartPath.Checkout -> {
                            back(HomeTabs.Action.CartPopFrom(top.id))
                            CheckoutAgent.activeScreen(pushed.state)
                                .map { HomeTabs.Action.CartElement(top.id, HomeTabs.CartPathAction.Checkout(it)) }
                                .identified("#${top.id}")
                        }
                        // The order is placed: there is no going back to checkout.
                        is HomeTabs.CartPath.Confirmation -> OrderConfirmationAgent.activeScreen(pushed.state)
                            .map { HomeTabs.Action.CartElement(top.id, HomeTabs.CartPathAction.Confirmation(it)) }
                            .identified("#${top.id}")
                    }
                }
            }
            HomeTabs.Tab.ORDERS -> {
                val top = state.ordersPath.top
                if (top != null) {
                    back(HomeTabs.Action.OrdersPopFrom(top.id))
                    OrderDetailAgent.activeScreen(top.screen).map { HomeTabs.Action.OrdersElement(top.id, it) }.identified("#${top.id}")
                } else {
                    OrdersListAgent.activeScreen(state.ordersList).map { HomeTabs.Action.OrdersList(it) }
                }
            }
            HomeTabs.Tab.PROFILE -> ProfileAgent.activeScreen(state.profile).map { HomeTabs.Action.Profile(it) }
        }
        return screen.identified("home#${state.id}").appending(commands)
    }

    override val registry: List<ScreenDoc>
        get() {
            val tab = CommandDoc("tab", "<$tabs>", TAB_HELP, "HomeTabs")
            val back = CommandDoc("back", null, BACK_HELP, "HomeTabs")
            return ShopFeedAgent.screenDocs.map { it.inheriting(listOf(tab)) } +
                ProductDetailAgent.screenDocs.map { it.inheriting(listOf(tab, back)) } +
                CartAgent.screenDocs.map { it.inheriting(listOf(tab)) } +
                CheckoutAgent.screenDocs.map { it.inheriting(listOf(tab, back)) } +
                OrderConfirmationAgent.screenDocs.map { it.inheriting(listOf(tab)) } +
                OrdersListAgent.screenDocs.map { it.inheriting(listOf(tab)) } +
                OrderDetailAgent.screenDocs.map { it.inheriting(listOf(tab, back)) } +
                ProfileAgent.screenDocs.map { it.inheriting(listOf(tab)) }
        }
}
