package io.github.olbartek.agentctl.examples.agentshop.models

import java.time.Instant

/** A registered user of the (mock) backend. */
data class User(val id: String, val name: String, val email: String)

/** An authenticated session, as returned by every successful sign-in. */
data class Session(val user: User, val token: String)

/** What the shop sells, and what a shopper picks as interests during onboarding. */
enum class ProductCategory(override val code: String) : Coded {
    SHOES("shoes"),
    BAGS("bags"),
    WATCHES("watches"),
    JACKETS("jackets"),
    ACCESSORIES("accessories"),
    HOME("home"),
    ;

    companion object {
        fun ofCode(code: String): ProductCategory? = entries.firstOrNull { it.code == code }
    }
}

/** One product in the catalog. `id` is the SKU agents type: `open 101`. */
data class Product(
    val id: Int,
    val name: String,
    val category: ProductCategory,
    val priceCents: Int,
    /** Empty when the product comes in one size. */
    val sizes: List<String> = emptyList(),
    val inStock: Boolean = true,
)

/**
 * A product in the cart, in one size. Its id is what `inc`, `dec` and `remove` take: `101-42`, or `103` for a product
 * without sizes.
 */
data class CartLine(val product: Product, val size: String?, val quantity: Int) {
    val id: String get() = if (size != null) "${product.id}-$size" else "${product.id}"
    val totalCents: Int get() = product.priceCents * quantity
}

/** A shipping address. */
data class Address(val name: String = "", val street: String = "", val city: String = "", val zip: String = "") {
    /** Every field filled in (a zip may still be malformed; see [isValidZip]). */
    val isComplete: Boolean get() = listOf(name, street, city, zip).all { it.trimmingWhitespaces().isNotEmpty() }
}

/** What the account server knows about a shopper beyond their session. */
data class AccountProfile(
    /** A new account (registered, or first seen through Google) goes through onboarding once. */
    val needsOnboarding: Boolean,
    val interests: List<ProductCategory> = emptyList(),
    /** The address saved during onboarding, which prefills checkout. */
    val address: Address? = null,
)

/** What a shopper chose during onboarding. */
data class OnboardingAnswers(val interests: List<ProductCategory>, val address: Address?, val notifications: Boolean)

enum class OrderStatus(override val code: String) : Coded {
    PENDING("pending"),
    SHIPPED("shipped"),
    DELIVERED("delivered"),
    CANCELLED("cancelled"),
}

data class OrderItem(val name: String, val quantity: Int, val unitPriceCents: Int) {
    val totalCents: Int get() = quantity * unitPriceCents
}

data class Order(val id: Int, val status: OrderStatus, val placedOn: Instant, val items: List<OrderItem>) {
    val totalCents: Int get() = items.sumOf { it.totalCents }

    /** Only pending orders can be cancelled. */
    val isCancellable: Boolean get() = status == OrderStatus.PENDING
}

/** What checkout sends to place an order. */
data class OrderRequest(
    val lines: List<CartLine>,
    val address: Address,
    val shippingCents: Int,
    val discountCents: Int,
    /** `null` for Apple Pay. */
    val cardNumber: String?,
)
