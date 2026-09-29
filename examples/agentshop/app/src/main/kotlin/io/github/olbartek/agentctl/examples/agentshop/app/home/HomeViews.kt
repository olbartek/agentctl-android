package io.github.olbartek.agentctl.examples.agentshop.app.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.olbartek.agentctl.examples.agentshop.app.design.ErrorView
import io.github.olbartek.agentctl.examples.agentshop.app.design.EmptyStateView
import io.github.olbartek.agentctl.examples.agentshop.app.design.IconAction
import io.github.olbartek.agentctl.examples.agentshop.app.design.InlineError
import io.github.olbartek.agentctl.examples.agentshop.app.design.LoadingView
import io.github.olbartek.agentctl.examples.agentshop.app.design.NavBar
import io.github.olbartek.agentctl.examples.agentshop.app.design.Palette
import io.github.olbartek.agentctl.examples.agentshop.app.design.PrimaryButton
import io.github.olbartek.agentctl.examples.agentshop.app.design.Rule
import io.github.olbartek.agentctl.examples.agentshop.app.design.Typography
import io.github.olbartek.agentctl.examples.agentshop.app.design.capitalized
import io.github.olbartek.agentctl.examples.agentshop.app.design.message
import io.github.olbartek.agentctl.examples.agentshop.app.design.screenTag
import io.github.olbartek.agentctl.examples.agentshop.app.design.summaryValue
import io.github.olbartek.agentctl.examples.agentshop.app.shop.CartView
import io.github.olbartek.agentctl.examples.agentshop.app.shop.CheckoutView
import io.github.olbartek.agentctl.examples.agentshop.app.shop.OrderConfirmationView
import io.github.olbartek.agentctl.examples.agentshop.app.shop.ProductDetailView
import io.github.olbartek.agentctl.examples.agentshop.app.shop.ShopFeedView
import io.github.olbartek.agentctl.examples.agentshop.home.HomeTabs
import io.github.olbartek.agentctl.examples.agentshop.home.OrderDetail
import io.github.olbartek.agentctl.examples.agentshop.home.OrderDetailAgent
import io.github.olbartek.agentctl.examples.agentshop.home.OrdersList
import io.github.olbartek.agentctl.examples.agentshop.home.OrdersListAgent
import io.github.olbartek.agentctl.examples.agentshop.home.Profile
import io.github.olbartek.agentctl.examples.agentshop.home.ProfileAgent
import io.github.olbartek.agentctl.examples.agentshop.models.Order
import io.github.olbartek.agentctl.examples.agentshop.models.formatCents
import io.github.olbartek.agentctl.examples.agentshop.models.formatDay

private data class TabItem(val tab: HomeTabs.Tab, val title: String, val icon: ImageVector)

private val tabItems = listOf(
    TabItem(HomeTabs.Tab.SHOP, "Shop", Icons.Filled.Home),
    TabItem(HomeTabs.Tab.CART, "Cart", Icons.Filled.ShoppingCart),
    TabItem(HomeTabs.Tab.ORDERS, "Orders", Icons.AutoMirrored.Filled.List),
    TabItem(HomeTabs.Tab.PROFILE, "Profile", Icons.Filled.Person),
)

/**
 * The signed-in area: four tabs, each a stack whose top screen is shown. Selecting the selected tab pops it to its
 * first screen (the reducer does that, as iOS's tab bar does). Tab items are tagged `HomeTabs.tab.<tab>`.
 */
@Composable
fun HomeTabsView(state: HomeTabs.State, send: (HomeTabs.Action) -> Unit) {
    Scaffold(
        containerColor = Palette.background,
        bottomBar = {
            NavigationBar(containerColor = Palette.surfaceTint) {
                for (item in tabItems) {
                    NavigationBarItem(
                        selected = state.selectedTab == item.tab,
                        onClick = { send(HomeTabs.Action.TabSelected(item.tab)) },
                        icon = {
                            if (item.tab == HomeTabs.Tab.CART && state.cart.itemCount > 0) {
                                BadgedBox(badge = { Badge { Text("${state.cart.itemCount}") } }) { Icon(item.icon, contentDescription = null) }
                            } else {
                                Icon(item.icon, contentDescription = null)
                            }
                        },
                        label = { Text(item.title) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Palette.brand, selectedTextColor = Palette.brand),
                        modifier = Modifier.testTag("HomeTabs.tab.${item.tab.code}"),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            // Each tab is its own screen tree: leaving a tab and coming back makes its screen appear again.
            key(state.selectedTab) {
                when (state.selectedTab) {
                    HomeTabs.Tab.SHOP -> {
                        val top = state.shopPath.top
                        if (top == null) {
                            ShopFeedView(state.shop) { send(HomeTabs.Action.Shop(it)) }
                        } else {
                            key(top.id) {
                                val pop = { send(HomeTabs.Action.ShopPopFrom(top.id)) }
                                BackHandler(onBack = pop)
                                ProductDetailView(top.screen, onBack = pop) { send(HomeTabs.Action.ShopElement(top.id, it)) }
                            }
                        }
                    }
                    HomeTabs.Tab.CART -> {
                        val top = state.cartPath.top
                        if (top == null) {
                            CartView(state.cart) { send(HomeTabs.Action.Cart(it)) }
                        } else {
                            key(top.id) {
                                when (val screen = top.screen) {
                                    is HomeTabs.CartPath.Checkout -> {
                                        val pop = { send(HomeTabs.Action.CartPopFrom(top.id)) }
                                        BackHandler(onBack = pop)
                                        CheckoutView(screen.state, onBack = pop) {
                                            send(HomeTabs.Action.CartElement(top.id, HomeTabs.CartPathAction.Checkout(it)))
                                        }
                                    }
                                    // The order is placed: there is no going back to checkout.
                                    is HomeTabs.CartPath.Confirmation -> {
                                        BackHandler {}
                                        OrderConfirmationView(screen.state) {
                                            send(HomeTabs.Action.CartElement(top.id, HomeTabs.CartPathAction.Confirmation(it)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    HomeTabs.Tab.ORDERS -> {
                        val top = state.ordersPath.top
                        if (top == null) {
                            OrdersListView(state.ordersList) { send(HomeTabs.Action.OrdersList(it)) }
                        } else {
                            key(top.id) {
                                val pop = { send(HomeTabs.Action.OrdersPopFrom(top.id)) }
                                BackHandler(onBack = pop)
                                OrderDetailView(top.screen, onBack = pop) { send(HomeTabs.Action.OrdersElement(top.id, it)) }
                            }
                        }
                    }
                    HomeTabs.Tab.PROFILE -> ProfileView(state.profile) { send(HomeTabs.Action.Profile(it)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersListView(state: OrdersList.State, send: (OrdersList.Action) -> Unit) {
    LaunchedEffect(Unit) { send(OrdersList.Action.OnAppear) }
    Column(Modifier.fillMaxSize().screenTag(OrdersListAgent.screenPath(state))) {
        NavBar("Orders") { IconAction(Icons.Filled.Refresh, "Refresh", "OrdersList.refresh") { send(OrdersList.Action.Refresh) } }
        // How many orders there are, in words; UI tests read it as `OrdersList.orders`.
        Text(
            if (state.orders.size == 1) "1 order" else "${state.orders.size} orders",
            style = Typography.caption.copy(color = Palette.textSecondary),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).summaryValue("OrdersList.orders", "${state.orders.size}"),
        )
        val error = state.error
        when (state.phase) {
            OrdersList.Phase.LOADING -> LoadingView("Loading orders…", Modifier.fillMaxSize())
            OrdersList.Phase.FAILED -> ErrorView(error?.message ?: "", code = error?.code, retryTag = "OrdersList.retry", modifier = Modifier.fillMaxSize()) {
                send(OrdersList.Action.Retry)
            }
            OrdersList.Phase.EMPTY -> EmptyStateView("No orders yet", "Orders you place will show up here.", Icons.AutoMirrored.Filled.List, Modifier.fillMaxSize())
            OrdersList.Phase.LOADED -> PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = { send(OrdersList.Action.Refresh) },
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    if (error != null) {
                        InlineError(error.message, code = error.code, modifier = Modifier.padding(vertical = 8.dp))
                        TextButton(onClick = { send(OrdersList.Action.Retry) }, modifier = Modifier.testTag("OrdersList.retry")) { Text("Try again") }
                    }
                    for (order in state.orders) {
                        OrderRow(order, Modifier.testTag("OrdersList.open.${order.id}").clickable(role = Role.Button) {
                            send(OrdersList.Action.OrderTapped(order.id))
                        })
                        Rule()
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderRow(order: Order, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Order #${order.id}", style = Typography.headline)
            Text(formatDay(order.placedOn), style = Typography.body.copy(color = Palette.textSecondary))
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(formatCents(order.totalCents), style = Typography.headline)
            Text(capitalized(order.status.code), style = Typography.caption.copy(color = Palette.textSecondary))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.border)
    }
}

@Composable
fun OrderDetailView(state: OrderDetail.State, onBack: () -> Unit, send: (OrderDetail.Action) -> Unit) {
    LaunchedEffect(Unit) { send(OrderDetail.Action.OnAppear) }
    Column(Modifier.fillMaxSize().screenTag(OrderDetailAgent.screenPath(state))) {
        NavBar("Order #${state.orderID}", large = false, onBack = onBack)
        val order = state.order
        val error = state.error
        when {
            order != null -> Column(
                Modifier.fillMaxSize().background(Palette.surfaceTint).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Section {
                    LabeledRow("Status", capitalized(order.status.code), Modifier.summaryValue("OrderDetail.status", order.status.code))
                    Rule()
                    LabeledRow("Placed on", formatDay(order.placedOn))
                }
                Text("ITEMS", style = Typography.caption.copy(color = Palette.textSecondary), modifier = Modifier.padding(start = 16.dp))
                Section {
                    for (item in order.items) {
                        LabeledRow("${item.name} × ${item.quantity}", formatCents(item.totalCents))
                        Rule()
                    }
                    LabeledRow("Total", formatCents(order.totalCents), Modifier.summaryValue("OrderDetail.total", formatCents(order.totalCents)), bold = true)
                }
                if (error != null) InlineError(error.message, code = error.code)
                if (order.isCancellable) {
                    PrimaryButton("Cancel order", isLoading = state.isCancelling, isEnabled = state.canCancel, testTag = "OrderDetail.cancel") {
                        send(OrderDetail.Action.CancelTapped)
                    }
                }
            }
            error != null -> ErrorView(error.message, code = error.code, retryTag = "OrderDetail.retry", modifier = Modifier.fillMaxSize()) {
                send(OrderDetail.Action.Retry)
            }
            else -> LoadingView("Loading order…", Modifier.fillMaxSize())
        }
    }
}

/** A grouped list section, as the reference's `List` draws one. */
@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.background).padding(horizontal = 16.dp)) { content() }
}

/** The reference's `LabeledContent`: a label and its value on one row. `valueModifier` tags the value. */
@Composable
private fun LabeledRow(label: String, value: String, valueModifier: Modifier = Modifier, bold: Boolean = false) {
    val style = if (bold) Typography.headline else Typography.body.copy(color = Palette.textBlack)
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(label, style = style, modifier = Modifier.weight(1f))
        Text(value, style = if (bold) style else style.copy(color = Palette.textSecondary), textAlign = TextAlign.End, modifier = valueModifier)
    }
}

@Composable
fun ProfileView(state: Profile.State, send: (Profile.Action) -> Unit) {
    Column(Modifier.fillMaxSize().screenTag(ProfileAgent.screenPath(state)).background(Palette.surfaceTint)) {
        NavBar("Profile")
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Section {
                LabeledRow("Name", state.user.name, Modifier.summaryValue("Profile.name", state.user.name))
                Rule()
                LabeledRow("Email", state.user.email, Modifier.summaryValue("Profile.email", state.user.email))
            }
            Section {
                Text(
                    "Log out",
                    style = Typography.body.copy(color = Palette.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("Profile.logout")
                        .clickable(role = Role.Button) { send(Profile.Action.LogoutTapped) }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
    val alert = state.alert
    if (alert != null) {
        AlertDialog(
            onDismissRequest = { send(Profile.Action.AlertDismissed) },
            title = { Text(alert.title) },
            text = { Text(alert.message) },
            confirmButton = {
                TextButton(onClick = { send(Profile.Action.ConfirmLogoutTapped) }, modifier = Modifier.testTag("Profile.confirm")) {
                    Text(alert.confirm, color = Palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { send(Profile.Action.AlertDismissed) }, modifier = Modifier.testTag("Profile.dismiss")) { Text(alert.cancel) }
            },
        )
    }
}
