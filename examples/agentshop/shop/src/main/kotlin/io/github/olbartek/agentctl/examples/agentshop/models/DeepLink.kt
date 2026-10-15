package io.github.olbartek.agentctl.examples.agentshop.models

import java.net.URI

/** The links AgentShop opens: `agentshop://product/<id>`, `agentshop://order/<id>` and `agentshop://shop`. */
sealed interface DeepLink {
    data class Product(val id: Int) : DeepLink
    data class Order(val id: Int) : DeepLink
    data object Shop : DeepLink

    companion object {
        /** `null` for a URL that is not one of AgentShop's links. */
        fun of(url: URI): DeepLink? {
            if (url.scheme != "agentshop") return null
            val host = url.host ?: return null
            val path = (url.path ?: "").split("/").filter { it.isNotEmpty() }
            return when {
                host == "shop" && path.isEmpty() -> Shop
                host == "product" && path.size == 1 -> path[0].toIntOrNull()?.let(::Product)
                host == "order" && path.size == 1 -> path[0].toIntOrNull()?.let(::Order)
                else -> null
            }
        }
    }
}
