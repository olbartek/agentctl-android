package io.github.olbartek.agentctl.examples.agentshop.app.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import io.github.olbartek.agentctl.examples.agentshop.navigation.Stack
import io.github.olbartek.agentctl.examples.agentshop.navigation.StackElement

/** How long a push or a pop slides for: about as long as a UIKit navigation transition. */
private const val TRANSITION_MILLIS = 350

/**
 * A navigation stack on screen: [root] when nothing is pushed, else the top element. A push slides the new screen
 * in from the end and a pop slides it back out, as a navigation stack does on iOS, so the app is busy for a moment
 * after either: `settle` over the agent bridge waits that out (CONTRACT.md §8.5).
 *
 * Each element is its own screen tree (a new push appears again, as in a navigation stack), and the screen sliding
 * out keeps the state it last had, so a popped screen leaves as it was.
 */
@Composable
fun <P> StackHost(stack: Stack<P>, root: @Composable () -> Unit, element: @Composable (StackElement<P>) -> Unit) {
    AnimatedContent(
        targetState = stack,
        contentKey = { it.top?.id },
        transitionSpec = {
            val push = targetState.elements.size >= initialState.elements.size
            val sign = if (push) 1 else -1
            slideInHorizontally(tween(TRANSITION_MILLIS)) { sign * it } togetherWith
                slideOutHorizontally(tween(TRANSITION_MILLIS)) { -sign * it }
        },
        label = "stack ${stack.name}",
    ) { shown ->
        when (val top = shown.top) {
            null -> root()
            else -> element(top)
        }
    }
}
