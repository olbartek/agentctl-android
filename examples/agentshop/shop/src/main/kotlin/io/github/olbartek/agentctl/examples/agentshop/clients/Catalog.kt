package io.github.olbartek.agentctl.examples.agentshop.clients

import io.github.olbartek.agentctl.MockMethod
import io.github.olbartek.agentctl.examples.agentshop.models.CartError
import io.github.olbartek.agentctl.examples.agentshop.models.CartException
import io.github.olbartek.agentctl.examples.agentshop.models.CatalogError
import io.github.olbartek.agentctl.examples.agentshop.models.CatalogException
import io.github.olbartek.agentctl.examples.agentshop.models.Product
import io.github.olbartek.agentctl.examples.agentshop.models.ProductCategory
import io.github.olbartek.agentctl.examples.agentshop.models.ShopCalls
import io.github.olbartek.agentctl.examples.agentshop.models.codeOf
import io.github.olbartek.agentctl.examples.agentshop.models.trimmingWhitespaces

/** The product catalog. Everything is mocked: [live] returns [Catalog.products]. */
class CatalogClient(val fetchProducts: suspend () -> List<Product> = { unimplemented("catalog.fetchProducts") }) {
    companion object {
        fun live(calls: ShopCalls): CatalogClient = CatalogClient(
            fetchProducts = {
                calls.call("catalog.fetchProducts", { CatalogException(codeOf<CatalogError>(it) ?: CatalogError.NETWORK) }) {
                    Catalog.products
                }
            },
        )

        val mockMethods: List<MockMethod> = listOf(MockMethod("catalog.fetchProducts", CatalogError.entries.map { it.code }))
    }
}

/** Twelve products, two per category, in "featured" order. SKUs are 101–112 so agents can type them. */
object Catalog {
    val shoeSizes: List<String> = listOf("40", "41", "42", "43", "44")
    val jacketSizes: List<String> = listOf("S", "M", "L")

    val products: List<Product> = listOf(
        Product(101, "Trail Runner", ProductCategory.SHOES, 8900, shoeSizes),
        Product(102, "City Sneaker", ProductCategory.SHOES, 6400, shoeSizes),
        Product(103, "Canvas Tote", ProductCategory.BAGS, 2900),
        Product(104, "Leather Backpack", ProductCategory.BAGS, 12900),
        Product(105, "Field Watch", ProductCategory.WATCHES, 14900),
        Product(106, "Dive Watch", ProductCategory.WATCHES, 24900),
        Product(107, "Rain Jacket", ProductCategory.JACKETS, 11900, jacketSizes),
        Product(108, "Down Jacket", ProductCategory.JACKETS, 18900, jacketSizes),
        Product(109, "Wool Beanie", ProductCategory.ACCESSORIES, 1900, inStock = false),
        Product(110, "Sunglasses", ProductCategory.ACCESSORIES, 5900),
        Product(111, "Ceramic Mug", ProductCategory.HOME, 1400),
        Product(112, "Linen Throw", ProductCategory.HOME, 4900),
    )
}

/** A promo code the server accepted. */
data class Promo(val code: String, val percentOff: Int) {
    fun discount(subtotalCents: Int): Int = subtotalCents * percentOff / 100
}

/** Promo codes. Everything is mocked: `SAVE10` is 10 % off, `HALF` 50 %; any other code is `invalidPromo`. */
class CartClient(val applyPromo: suspend (code: String) -> Promo = { unimplemented("cart.applyPromo") }) {
    companion object {
        val promos: Map<String, Int> = mapOf("SAVE10" to 10, "HALF" to 50)

        fun live(calls: ShopCalls): CartClient = CartClient(
            applyPromo = { code ->
                calls.call("cart.applyPromo", { CartException(codeOf<CartError>(it) ?: CartError.NETWORK) }) {
                    val normalized = code.trimmingWhitespaces().uppercase()
                    val percent = promos[normalized] ?: throw CartException(CartError.INVALID_PROMO)
                    Promo(normalized, percent)
                }
            },
        )

        /** Only `network`: an unknown code is how a script gets `invalidPromo`. */
        val mockMethods: List<MockMethod> = listOf(MockMethod("cart.applyPromo", listOf(CartError.NETWORK.code)))
    }
}
