package io.github.olbartek.agentctl.examples.tinyapp

import io.github.olbartek.agentctl.MockBackend
import io.github.olbartek.agentctl.MockMethod

/** One row of the list. */
data class Item(val id: Int, val title: String) {
    companion object {
        /** What every fetch returns. A real app would have a server here; a constant is enough to drive the screens. */
        val seed: List<Item> = listOf(Item(1, "First item"), Item(2, "Second item"), Item(3, "Third item"))
    }
}

/**
 * Everything the list can report as `error=<code>`.
 *
 * The codes are what an agent sees and asserts on, so keep them short and stable. Two of them are what
 * [ItemsClient.fetch] can throw and `mock items.fetch <code>` can force; `notFound` is the screen's own, for an
 * `open <id>` that names an item the list does not have. That is why [ItemsClient.fetchErrors] lists the client's
 * codes explicitly instead of taking every case: `mock items.fetch notFound` is rightly rejected.
 */
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

/**
 * The app's one client. Everything is mocked, and every method goes through [MockBackend.call] so that the call
 * shows up as `calls=items.fetch` in the step output and can be made to fail with `mock`.
 */
class ItemsClient(val fetch: suspend () -> List<Item>) {
    companion object {
        /** The mock, on whichever backend the host hands the app: headless, live, or a release build's. */
        fun mock(backend: MockBackend): ItemsClient = ItemsClient(
            fetch = {
                backend.call("items.fetch", { code -> ItemsException(ItemsError.ofCode(code) ?: ItemsError.NETWORK) }) {
                    Item.seed
                }
            },
        )

        /** What a fetch can throw — not every [ItemsError], because `notFound` is the screen's, not the client's. */
        val fetchErrors: List<ItemsError> = listOf(ItemsError.NETWORK, ItemsError.TIMEOUT)

        /**
         * The methods `mock <method> <error>` accepts, with the errors each one can be made to throw. The CLI and the
         * generated docs list exactly these.
         */
        val mockMethods: List<MockMethod> = listOf(MockMethod("items.fetch", fetchErrors.map { it.code }))
    }
}
