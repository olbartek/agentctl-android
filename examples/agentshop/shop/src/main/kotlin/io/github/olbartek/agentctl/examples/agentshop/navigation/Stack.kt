package io.github.olbartek.agentctl.examples.agentshop.navigation

import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.EffectScope
import io.github.olbartek.agentctl.Next

/** One screen pushed on a [Stack]. The id is what tells two pushes of the same screen apart. */
data class StackElement<out P>(val id: Int, val screen: P)

/**
 * A navigation stack: the screens pushed above a container's root, the Kotlin stand-in for TCA's `StackState`.
 *
 * Ids come from [nextId] and are never reused, so they are deterministic and a re-pushed screen is a new element
 * (the runtime sends its appearance again). Every element's effects run in their own scope, [ElementScope], and
 * the [popFrom]/[removeAll] results carry the effect that cancels them, as TCA's `forEach` does for an element that
 * leaves the stack.
 *
 * @param name tells this stack's elements' scopes from another stack's in the same container.
 */
data class Stack<P>(val name: String, val elements: List<StackElement<P>> = emptyList(), val nextId: Int = 0) {
    val top: StackElement<P>? get() = elements.lastOrNull()

    fun isEmpty(): Boolean = elements.isEmpty()

    fun push(screen: P): Stack<P> = copy(elements = elements + StackElement(nextId, screen), nextId = nextId + 1)

    operator fun get(id: Int): StackElement<P>? = elements.firstOrNull { it.id == id }

    /** Replaces the screen of element `id`. */
    fun replacing(id: Int, screen: P): Stack<P> =
        copy(elements = elements.map { if (it.id == id) it.copy(screen = screen) else it })

    /** Pops element `id` and everything above it, and the effect that cancels what they were running. */
    fun popFrom(id: Int): Next<Stack<P>, Nothing> {
        val index = elements.indexOfFirst { it.id == id }
        if (index < 0) return Next(this)
        return removing(elements.subList(index, elements.size))
    }

    /** Pops everything, and the effect that cancels what the popped elements were running. */
    fun removeAll(): Next<Stack<P>, Nothing> = removing(elements)

    /** The scope element `id`'s effects run in: what [Effect.scoped] is given for it. */
    fun scope(id: Int): ElementScope = ElementScope(name, id)

    /**
     * Reduces an action for element `id` with `reduce`, returning the stack with the element's new screen and the
     * element's effect lifted by `embed` and scoped to the element. An action for an element that is no longer on the
     * stack is dropped, as a stack does.
     */
    fun <A, PA> reduceElement(id: Int, embed: (A) -> PA, reduce: (P) -> Next<P, A>?): Next<Stack<P>, PA> {
        val element = this[id] ?: return Next(this)
        val result = reduce(element.screen) ?: return Next(this)
        return Next(replacing(id, result.state), result.effect.map(embed).scoped(scope(id)))
    }

    private fun removing(removed: List<StackElement<P>>): Next<Stack<P>, Nothing> {
        if (removed.isEmpty()) return Next(this)
        val ids = removed.map { it.id }.toSet()
        return Next(
            copy(elements = elements.filter { it.id !in ids }),
            Effect.Merge(removed.map { Effect.cancel(EffectScope(scope(it.id))) }),
        )
    }
}

/** The effect scope of one stack element: see [Stack.scope]. */
data class ElementScope(val stack: String, val id: Int)
