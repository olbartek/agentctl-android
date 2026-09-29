package io.github.olbartek.agentctl.examples.agentshop.app.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.olbartek.agentctl.examples.agentshop.app.design.ASTextField
import io.github.olbartek.agentctl.examples.agentshop.app.design.Chip
import io.github.olbartek.agentctl.examples.agentshop.app.design.EmptyStateView
import io.github.olbartek.agentctl.examples.agentshop.app.design.ErrorView
import io.github.olbartek.agentctl.examples.agentshop.app.design.FieldKind
import io.github.olbartek.agentctl.examples.agentshop.app.design.IconAction
import io.github.olbartek.agentctl.examples.agentshop.app.design.InlineError
import io.github.olbartek.agentctl.examples.agentshop.app.design.LinkButton
import io.github.olbartek.agentctl.examples.agentshop.app.design.LoadingView
import io.github.olbartek.agentctl.examples.agentshop.app.design.NavBar
import io.github.olbartek.agentctl.examples.agentshop.app.design.Palette
import io.github.olbartek.agentctl.examples.agentshop.app.design.PrimaryButton
import io.github.olbartek.agentctl.examples.agentshop.app.design.Rule
import io.github.olbartek.agentctl.examples.agentshop.app.design.Typography
import io.github.olbartek.agentctl.examples.agentshop.app.design.capitalized
import io.github.olbartek.agentctl.examples.agentshop.app.design.message
import io.github.olbartek.agentctl.examples.agentshop.app.design.onOffValue
import io.github.olbartek.agentctl.examples.agentshop.app.design.rememberFieldText
import io.github.olbartek.agentctl.examples.agentshop.app.design.screenTag
import io.github.olbartek.agentctl.examples.agentshop.app.design.summaryValue
import io.github.olbartek.agentctl.examples.agentshop.app.design.uiTestValue
import io.github.olbartek.agentctl.examples.agentshop.models.CartError
import io.github.olbartek.agentctl.examples.agentshop.models.Coded
import io.github.olbartek.agentctl.examples.agentshop.models.OrdersError
import io.github.olbartek.agentctl.examples.agentshop.models.Product
import io.github.olbartek.agentctl.examples.agentshop.models.ProductCategory
import io.github.olbartek.agentctl.examples.agentshop.models.formatCents
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

// The shop's screens. Tags follow design/UiTestTags.kt: `<Screen>.<command>` for what a command taps or types into,
// `<Screen>.<command>.<argument>` for a choice, `<Screen>.<key>` for a shown value.

/** A category's picture: the reference uses SF Symbols; emoji stand in for them here. */
private val ProductCategory.symbol: String
    get() = when (this) {
        ProductCategory.SHOES -> "👟"
        ProductCategory.BAGS -> "👜"
        ProductCategory.WATCHES -> "⌚"
        ProductCategory.JACKETS -> "🧥"
        ProductCategory.ACCESSORIES -> "🕶"
        ProductCategory.HOME -> "☕"
    }

@Composable
fun ShopFeedView(state: ShopFeed.State, send: (ShopFeed.Action) -> Unit) {
    LaunchedEffect(Unit) { send(ShopFeed.Action.OnAppear) }
    Column(Modifier.fillMaxSize().screenTag(ShopFeedAgent.screenPath(state))) {
        NavBar("Shop") { IconAction(Icons.Filled.Refresh, "Refresh", "ShopFeed.refresh") { send(ShopFeed.Action.Refresh) } }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SearchField(state.query, onChange = { send(ShopFeed.Action.QueryChanged(it)) }, onClear = { send(ShopFeed.Action.ClearSearchTapped) })

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).summaryValue("ShopFeed.filter", state.filter?.code ?: "all"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("All", isOn = state.filter == null, testTag = "ShopFeed.filter.all") { send(ShopFeed.Action.FilterTapped(null)) }
                for (category in ProductCategory.entries) {
                    Chip(capitalized(category.code), isOn = state.filter == category, testTag = "ShopFeed.filter.${category.code}") {
                        send(ShopFeed.Action.FilterTapped(category))
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${state.visible.size} products",
                    style = Typography.caption.copy(color = Palette.textSubtle),
                    modifier = Modifier.summaryValue("ShopFeed.products", "${state.visible.size}"),
                )
                Spacer(Modifier.weight(1f))
                Row(Modifier.summaryValue("ShopFeed.sort", state.sort.code), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (sort in ShopFeed.Sort.entries) {
                        Chip(sortLabel(sort), isOn = state.sort == sort, testTag = "ShopFeed.sort.${sort.code}") { send(ShopFeed.Action.SortTapped(sort)) }
                    }
                }
            }

            val error = state.error
            if (error != null) {
                if (state.products.isEmpty()) {
                    ErrorView(error.message, code = error.code, retryTag = "ShopFeed.retry") { send(ShopFeed.Action.Retry) }
                } else {
                    InlineError(error.message, code = error.code)
                    TextButton(onClick = { send(ShopFeed.Action.Retry) }, modifier = Modifier.testTag("ShopFeed.retry")) { Text("Try again") }
                }
            } else if (state.isLoading && state.products.isEmpty()) {
                LoadingView("Loading products…", Modifier.height(200.dp))
            }

            // Not lazy: twelve rows, and every one exists for UI tests to find.
            Column {
                for (product in state.visible) {
                    ProductRow(product, Modifier.testTag("ShopFeed.open.${product.id}").clickable(role = Role.Button) {
                        send(ShopFeed.Action.ProductTapped(product.id))
                    })
                    Rule()
                }
            }
        }
    }
}

private fun sortLabel(sort: ShopFeed.Sort): String = when (sort) {
    ShopFeed.Sort.FEATURED -> "Featured"
    ShopFeed.Sort.PRICE_ASC -> "Price ↑"
    ShopFeed.Sort.PRICE_DESC -> "Price ↓"
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onClear: () -> Unit) {
    val (value, onValueChange) = rememberFieldText(query, onChange)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.surfaceTint).padding(horizontal = 12.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Typography.input.copy(fontWeight = null),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            cursorBrush = SolidColor(Palette.brand),
            modifier = Modifier.weight(1f).testTag("ShopFeed.search").semantics { uiTestValue = query },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) Text("Search products", style = Typography.input.copy(color = Palette.placeholder, fontWeight = null))
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Filled.Clear,
                contentDescription = "Clear",
                tint = Palette.textSecondary,
                modifier = Modifier.testTag("ShopFeed.clear-search").clickable(role = Role.Button, onClick = onClear),
            )
        }
    }
}

@Composable
private fun ProductRow(product: Product, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Palette.surfaceTint), contentAlignment = Alignment.Center) {
            Text(product.category.symbol, fontSize = 24.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(product.name, style = Typography.bodyMedium)
            Text(
                if (product.inStock) capitalized(product.category.code) else "Out of stock",
                style = Typography.caption.copy(color = if (product.inStock) Palette.textSecondary else Palette.error),
            )
        }
        Text(formatCents(product.priceCents), style = Typography.bodyMedium)
    }
}

@Composable
fun ProductDetailView(state: ProductDetail.State, onBack: () -> Unit, send: (ProductDetail.Action) -> Unit) {
    val product = state.product
    Column(Modifier.fillMaxSize().screenTag(ProductDetailAgent.screenPath(state))) {
        NavBar(product.name, large = false, onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)).background(Palette.surfaceTint), contentAlignment = Alignment.Center) {
                Text(product.category.symbol, fontSize = 56.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(product.name, style = Typography.screenTitle, modifier = Modifier.weight(1f).summaryValue("ProductDetail.name", product.name))
                IconButton(
                    onClick = { send(ProductDetail.Action.FavoriteToggled(!state.isFavorite)) },
                    modifier = Modifier.testTag("ProductDetail.favorite").semantics { contentDescription = "Favorite" }.onOffValue(state.isFavorite),
                ) {
                    Icon(if (state.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = null, tint = Palette.brand)
                }
            }
            Text(
                formatCents(product.priceCents),
                style = Typography.sectionLabel,
                modifier = Modifier.summaryValue("ProductDetail.price", formatCents(product.priceCents)),
            )
            if (!product.inStock) Text("Out of stock", style = Typography.bodyMedium.copy(color = Palette.error))

            if (product.sizes.isEmpty()) {
                Text("One size", style = Typography.caption.copy(color = Palette.textSecondary), modifier = Modifier.summaryValue("ProductDetail.size", "one-size"))
            } else {
                Text("Size", style = Typography.bodyMedium)
                Row(Modifier.summaryValue("ProductDetail.size", state.size ?: "none"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (size in product.sizes) {
                        val isOn = state.size == size
                        Box(
                            Modifier
                                .testTag("ProductDetail.size.$size")
                                .size(width = 48.dp, height = 40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isOn) Palette.brand else Palette.surfaceTint)
                                .clickable(role = Role.Button) { send(ProductDetail.Action.SizeTapped(size)) }
                                .onOffValue(isOn),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(size, style = Typography.bodyMedium.copy(color = if (isOn) Palette.onBrand else Palette.textPrimary))
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Quantity", style = Typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                RoundIconButton("−", "Fewer", "ProductDetail.qty-down") { send(ProductDetail.Action.QuantityDownTapped) }
                Text(
                    "${state.quantity}",
                    style = Typography.sectionLabel,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 24.dp).summaryValue("ProductDetail.qty", "${state.quantity}"),
                )
                RoundIconButton("+", "More", "ProductDetail.qty-up") { send(ProductDetail.Action.QuantityUpTapped) }
            }
            state.error?.let { error ->
                InlineError(
                    if (error == ProductDetail.Error.MAX_QUANTITY) "At most ${ProductDetail.MAX_QUANTITY} per order." else "At least one.",
                    code = error.code,
                )
            }

            PrimaryButton("Add to cart", isEnabled = state.canAdd, testTag = "ProductDetail.add-to-cart") { send(ProductDetail.Action.AddToCartTapped) }
            Text(
                if (state.added == 0) "Not in your cart yet" else "Added ${state.added} to your cart",
                style = Typography.caption.copy(color = Palette.textSubtle),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().summaryValue("ProductDetail.inCart", "${state.added}"),
            )
            LinkButton("View cart", testTag = "ProductDetail.view-cart", modifier = Modifier.align(Alignment.CenterHorizontally)) {
                send(ProductDetail.Action.ViewCartTapped)
            }
        }
    }
}

/** The reference's `minus.circle` / `plus.circle`: an outlined circle with a sign. */
@Composable
private fun RoundIconButton(sign: String, label: String, testTag: String, size: Int = 28, onClick: () -> Unit) {
    Box(
        Modifier
            .testTag(testTag)
            .size(size.dp)
            .clip(CircleShape)
            .border(1.5.dp, Palette.brand, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(sign, style = TextStyle(fontSize = (size * 0.6).sp, color = Palette.brand, textAlign = TextAlign.Center))
    }
}

@Composable
fun CartView(state: Cart.State, send: (Cart.Action) -> Unit) {
    Column(Modifier.fillMaxSize().screenTag(CartAgent.screenPath(state))) {
        NavBar("Cart")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.lines.isEmpty()) {
                EmptyStateView("Your cart is empty", "Products you add show up here.", Icons.Filled.ShoppingCart, Modifier.height(240.dp))
            }
            for (line in state.lines) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(line.product.name, style = Typography.bodyMedium)
                        Text(line.size?.let { "Size $it" } ?: "One size", style = Typography.caption.copy(color = Palette.textSecondary))
                    }
                    RoundIconButton("−", "Fewer", "Cart.dec.${line.id}", size = 22) { send(Cart.Action.DecrementTapped(line.id)) }
                    Text("${line.quantity}", style = Typography.body, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 20.dp))
                    RoundIconButton("+", "More", "Cart.inc.${line.id}", size = 22) { send(Cart.Action.IncrementTapped(line.id)) }
                    Text(formatCents(line.totalCents), style = Typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 72.dp))
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove",
                        tint = Palette.error,
                        modifier = Modifier.testTag("Cart.remove.${line.id}").clickable(role = Role.Button) { send(Cart.Action.RemoveTapped(line.id)) },
                    )
                }
                Rule()
            }
            val lines = if (state.lines.isEmpty()) "none" else state.lines.joinToString(",") { it.id }
            // Not shown: which lines the cart has, for UI tests (`Cart.lines`).
            Box(Modifier.fillMaxWidth().height(1.dp).summaryValue("Cart.lines", lines))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ASTextField("Promo code", state.promoCode, { send(Cart.Action.PromoCodeChanged(it)) }, Modifier.weight(1f), testTag = "Cart.promo-code")
                TextButton(
                    onClick = { send(Cart.Action.ApplyPromoTapped) },
                    enabled = state.canApplyPromo,
                    modifier = Modifier.testTag("Cart.apply-promo"),
                ) { Text("Apply") }
            }
            state.promo?.let { promo ->
                Row(Modifier.summaryValue("Cart.promo", promo.code), verticalAlignment = Alignment.CenterVertically) {
                    Text("Promo ${promo.code}: ${promo.percentOff}% off", style = Typography.caption, modifier = Modifier.weight(1f))
                    LinkButton("Remove promo", style = Typography.caption, testTag = "Cart.clear-promo") { send(Cart.Action.ClearPromoTapped) }
                }
            }
            state.error?.let { error ->
                InlineError(if (error == CartError.INVALID_PROMO) "That code isn't valid." else "The network is unreachable. Try again.", code = error.code)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AmountRow("Items", "${state.itemCount}", "Cart.items")
                AmountRow("Subtotal", formatCents(state.subtotalCents), "Cart.subtotal")
                AmountRow("Discount", formatCents(state.discountCents), "Cart.discount")
                AmountRow("Total", formatCents(state.totalCents), "Cart.total", Typography.sectionLabel)
            }
            PrimaryButton("Checkout", isEnabled = state.canCheckout, testTag = "Cart.checkout") { send(Cart.Action.CheckoutTapped) }
        }
    }
}

@Composable
private fun AmountRow(label: String, value: String, tag: String, style: TextStyle = Typography.body) {
    Row {
        Text(label, style = style, modifier = Modifier.weight(1f))
        Text(value, style = style, modifier = Modifier.summaryValue(tag, value))
    }
}

@Composable
fun CheckoutView(state: Checkout.State, onBack: () -> Unit, send: (Checkout.Action) -> Unit) {
    LaunchedEffect(Unit) { send(Checkout.Action.OnAppear) }
    Column(Modifier.fillMaxSize().screenTag(CheckoutAgent.screenPath(state))) {
        NavBar("Checkout", large = false, onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Ship to", style = Typography.sectionLabel)
            ASTextField("Full name", state.address.name, { send(Checkout.Action.NameChanged(it)) }, kind = FieldKind.NAME, testTag = "Checkout.name")
            ASTextField("Street", state.address.street, { send(Checkout.Action.StreetChanged(it)) }, testTag = "Checkout.street")
            ASTextField("City", state.address.city, { send(Checkout.Action.CityChanged(it)) }, testTag = "Checkout.city")
            ASTextField("Zip code", state.address.zip, { send(Checkout.Action.ZipChanged(it)) }, testTag = "Checkout.zip")

            Text("Shipping", style = Typography.sectionLabel)
            ChoiceRow(Checkout.Shipping.entries, state.shipping, "Checkout.shipping", label = {
                if (it == Checkout.Shipping.STANDARD) "Standard (free)" else "Express (+${formatCents(Checkout.EXPRESS_SHIPPING_CENTS)})"
            }) { send(Checkout.Action.ShippingTapped(it)) }

            Text("Payment", style = Typography.sectionLabel)
            ChoiceRow(Checkout.Payment.entries, state.payment, "Checkout.payment", label = {
                if (it == Checkout.Payment.CARD) "Card" else "Apple Pay"
            }) { send(Checkout.Action.PaymentTapped(it)) }
            if (state.payment == Checkout.Payment.CARD) {
                ASTextField("Card number", state.cardNumber, { send(Checkout.Action.CardNumberChanged(it)) }, kind = FieldKind.ONE_TIME_CODE, testTag = "Checkout.card")
            }

            state.error?.let { InlineError(message(it), code = it.code) }
            Row {
                Text("Total", style = Typography.sectionLabel, modifier = Modifier.weight(1f))
                Text(formatCents(state.totalCents), style = Typography.sectionLabel, modifier = Modifier.summaryValue("Checkout.total", formatCents(state.totalCents)))
            }
            PrimaryButton("Place order", isLoading = state.isPlacing, isEnabled = state.canPlace, testTag = "Checkout.place-order") {
                send(Checkout.Action.PlaceOrderTapped)
            }
        }
    }
}

private fun message(error: Checkout.Error): String = when (error) {
    Checkout.Error.INVALID_ZIP -> "Enter a five-digit zip code."
    Checkout.Error.INVALID_CARD -> "Enter a 16-digit card number."
    Checkout.Error.PAYMENT_DECLINED -> OrdersError.PAYMENT_DECLINED.message
    Checkout.Error.NETWORK -> OrdersError.NETWORK.message
}

@Composable
private fun <C : Coded> ChoiceRow(choices: List<C>, selected: C, tag: String, label: (C) -> String, onSelect: (C) -> Unit) {
    Row(Modifier.fillMaxWidth().summaryValue(tag, selected.code), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (choice in choices) {
            val isOn = choice == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .testTag("$tag.${choice.code}")
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isOn) Palette.brand else Palette.surfaceTint)
                    .clickable(role = Role.Button) { onSelect(choice) }
                    .onOffValue(isOn),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(choice), style = Typography.bodyMedium.copy(color = if (isOn) Palette.onBrand else Palette.textPrimary))
            }
        }
    }
}

/** The order is placed: there is no going back to checkout, so no back button. */
@Composable
fun OrderConfirmationView(state: OrderConfirmation.State, send: (OrderConfirmation.Action) -> Unit) {
    Column(
        Modifier.fillMaxSize().screenTag(OrderConfirmationAgent.screenPath(state)).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.checkboxOn, modifier = Modifier.size(72.dp))
        Text("Thanks for your order!", style = Typography.screenTitle)
        Text("Order #${state.order.id}", style = Typography.sectionLabel, modifier = Modifier.summaryValue("OrderConfirmation.order", "${state.order.id}"))
        Text(
            formatCents(state.order.totalCents),
            style = Typography.body,
            modifier = Modifier.summaryValue("OrderConfirmation.total", formatCents(state.order.totalCents)),
        )
        PrimaryButton("View order", testTag = "OrderConfirmation.view-order") { send(OrderConfirmation.Action.ViewOrderTapped) }
        LinkButton("Continue shopping", testTag = "OrderConfirmation.continue-shopping") { send(OrderConfirmation.Action.ContinueShoppingTapped) }
    }
}
