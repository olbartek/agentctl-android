package io.github.olbartek.agentctl.examples.agentshop.shop

import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.CommandGate
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Next
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.examples.agentshop.clients.CatalogClient
import io.github.olbartek.agentctl.examples.agentshop.models.CatalogError
import io.github.olbartek.agentctl.examples.agentshop.models.Coded
import io.github.olbartek.agentctl.examples.agentshop.models.Outcome
import io.github.olbartek.agentctl.examples.agentshop.models.Product
import io.github.olbartek.agentctl.examples.agentshop.models.ProductCategory
import io.github.olbartek.agentctl.examples.agentshop.models.attempt
import io.github.olbartek.agentctl.examples.agentshop.models.trimmingWhitespaces
import io.github.olbartek.agentctl.invalidArgument
import io.github.olbartek.agentctl.next

/**
 * The shop's home: every product, filterable by category, searchable by name and sortable by price. It loads once,
 * when it first appears; a failed load shows the error with retry, and a failed refresh keeps the products.
 */
object ShopFeed {
    enum class Sort(override val code: String) : Coded {
        FEATURED("featured"),
        PRICE_ASC("price-asc"),
        PRICE_DESC("price-desc"),
    }

    data class State(
        val products: List<Product> = emptyList(),
        /** `null` is "all". */
        val filter: ProductCategory? = null,
        val query: String = "",
        val sort: Sort = Sort.FEATURED,
        val isLoading: Boolean = false,
        val hasLoaded: Boolean = false,
        val error: CatalogError? = null,
    ) {
        /** What the list shows: filtered by category and name, then sorted. */
        val visible: List<Product>
            get() {
                val query = query.trimmingWhitespaces().lowercase()
                val matching = products.filter { product ->
                    (filter == null || product.category == filter) && (query.isEmpty() || query in product.name.lowercase())
                }
                return when (sort) {
                    Sort.FEATURED -> matching
                    Sort.PRICE_ASC -> matching.sortedWith(compareBy<Product>({ it.priceCents }, { it.id }))
                    Sort.PRICE_DESC -> matching.sortedWith(compareByDescending<Product> { it.priceCents }.thenBy { it.id })
                }
            }
    }

    sealed interface Action {
        data class QueryChanged(val query: String) : Action
        data object OnAppear : Action
        data object Refresh : Action
        data object Retry : Action
        data class ProductsResponse(val result: Outcome<List<Product>, CatalogError>) : Action

        /** `null` is "all". */
        data class FilterTapped(val filter: ProductCategory?) : Action
        data class SortTapped(val sort: Sort) : Action
        data object ClearSearchTapped : Action
        data class ProductTapped(val id: Int) : Action

        sealed interface Delegate : Action {
            data class OpenProduct(val product: Product) : Delegate
        }
    }

    private object LoadId

    fun reducer(catalogClient: CatalogClient): Reducer<State, Action> {
        fun load(state: State): Next<State, Action> = next(
            state.copy(isLoading = true, error = null),
            Effect.run<Action> { send -> send(Action.ProductsResponse(attempt(CatalogError::of) { catalogClient.fetchProducts() })) }
                .cancellable(LoadId, cancelInFlight = true),
        )

        return Reducer { state, action ->
            when (action) {
                is Action.QueryChanged -> next(state.copy(query = action.query))
                Action.OnAppear -> if (state.hasLoaded || state.isLoading) next(state) else load(state)
                Action.Refresh, Action.Retry -> load(state)
                is Action.ProductsResponse -> when (val result = action.result) {
                    is Outcome.Success -> next(state.copy(isLoading = false, hasLoaded = true, products = result.value))
                    is Outcome.Failure -> next(state.copy(isLoading = false, hasLoaded = true, error = result.error))
                }
                is Action.FilterTapped -> next(state.copy(filter = action.filter))
                is Action.SortTapped -> next(state.copy(sort = action.sort))
                Action.ClearSearchTapped -> next(state.copy(query = ""))
                is Action.ProductTapped -> {
                    val product = state.products.firstOrNull { it.id == action.id } ?: return@Reducer next(state)
                    next(state, Effect.send(Action.Delegate.OpenProduct(product)))
                }
                is Action.Delegate -> next(state)
            }
        }
    }
}

object ShopFeedAgent : AgentScreen<ShopFeed.State, ShopFeed.Action> {
    /** `all` for no filter, then every category. */
    private val filters: List<Pair<String, ProductCategory?>> = listOf("all" to null) + ProductCategory.entries.map { it.code to it }

    override val screenPaths: List<String> = listOf("home/shop")

    override fun screenPath(state: ShopFeed.State): String = "home/shop"

    override val summaryKeys: List<String> = listOf("products", "filter", "query", "sort", "loading")

    override fun summary(state: ShopFeed.State): List<SummaryItem> = listOf(
        SummaryItem("products", state.visible.size),
        SummaryItem("filter", state.filter?.code ?: "all"),
        SummaryItem("query", state.query),
        SummaryItem("sort", state.sort.code),
        SummaryItem("loading", state.isLoading),
    )

    override fun errorCode(state: ShopFeed.State): String? = state.error?.code

    /** Headlessly there is no view to send this, so the runtime sends it when the screen becomes active. */
    override val onAppear: ShopFeed.Action = ShopFeed.Action.OnAppear

    override val commands: List<AgentCommand<ShopFeed.State, ShopFeed.Action>> = listOf(
        AgentCommand.choice("filter", filters, help = "Show one category, or all.") { ShopFeed.Action.FilterTapped(it) },
        AgentCommand.text("search", help = "Type in the search field; matches product names as you type.") {
            ShopFeed.Action.QueryChanged(it)
        },
        AgentCommand.action("clear-search", help = "Clear the search field.", action = ShopFeed.Action.ClearSearchTapped),
        AgentCommand.choice("sort", of = ShopFeed.Sort.entries, word = { it.code }, help = "Sort the products.") {
            ShopFeed.Action.SortTapped(it)
        },
        AgentCommand.parsing(
            "open",
            argument = "<sku>",
            help = "Open a product, e.g. open 101.",
            gate = CommandGate("products=0") { it.visible.isNotEmpty() },
        ) { text ->
            ShopFeed.Action.ProductTapped(text.toIntOrNull() ?: invalidArgument("expected a SKU such as 101"))
        },
        AgentCommand.action("refresh", help = "Load the catalog again.", action = ShopFeed.Action.Refresh),
        AgentCommand.action(
            "retry",
            help = "Load the catalog again after a failure.",
            action = ShopFeed.Action.Retry,
            gate = CommandGate("error=none") { it.error != null },
        ),
    )
}
