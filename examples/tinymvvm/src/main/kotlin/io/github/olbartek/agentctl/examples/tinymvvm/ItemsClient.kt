package io.github.olbartek.agentctl.examples.tinymvvm

import io.github.olbartek.agentctl.MockBackend
import io.github.olbartek.agentctl.MockMethod

/** One row of the list. */
data class Item(val id: Int, val title: String) {
    companion object {
        /** What every fetch returns: TinyApp's three items. */
        val seed: List<Item> = listOf(Item(1, "First item"), Item(2, "Second item"), Item(3, "Third item"))
    }
}

/** Everything the list can report as `error=<code>`: TinyApp's codes. */
enum class ItemsError(val code: String) {
    NETWORK("network"),
    TIMEOUT("timeout"),
    NOT_FOUND("notFound"),
    ;

    companion object {
        fun ofCode(code: String): ItemsError? = entries.firstOrNull { it.code == code }

        /** Anything else becomes `network`, so `error=` is always one of the documented codes. */
        fun of(error: Throwable): ItemsError = (error as? ItemsException)?.error ?: NETWORK
    }
}

class ItemsException(val error: ItemsError) : Exception(error.code)

/** The app's one client: a mock through [MockBackend.call], as TinyApp's, so `calls=` and `mock` see it. */
class ItemsClient(val fetch: suspend () -> List<Item>) {
    companion object {
        fun mock(backend: MockBackend): ItemsClient = ItemsClient(
            fetch = {
                backend.call("items.fetch", { code -> ItemsException(ItemsError.ofCode(code) ?: ItemsError.NETWORK) }) {
                    Item.seed
                }
            },
        )

        /** The methods `mock <method> <error>` accepts: TinyApp's. */
        val mockMethods: List<MockMethod> = listOf(MockMethod("items.fetch", listOf(ItemsError.NETWORK.code, ItemsError.TIMEOUT.code)))
    }
}
