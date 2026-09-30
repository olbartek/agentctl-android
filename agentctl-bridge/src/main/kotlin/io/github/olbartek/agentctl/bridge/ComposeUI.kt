package io.github.olbartek.agentctl.bridge

import androidx.compose.runtime.Recomposer

/**
 * Whether the app's Compose UI is idle: no recomposition pending and no frame awaited, which is what an animation
 * in flight (a screen sliding in or out, say) does. [AgentLaunch] makes it the live host's `isUIIdle`, so settling
 * waits for a navigation transition to end (CONTRACT.md §8.5).
 *
 * The bridge compiles against Compose but does not bring it: an app without Compose has no UI of it to wait for, and
 * is always idle here.
 */
internal object ComposeUI {
    private val present: Boolean = try {
        Class.forName("androidx.compose.runtime.Recomposer")
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }

    /** Call on the main thread. */
    fun isIdle(): Boolean = !present || Recomposers.idle()

    /** Kept apart, so nothing of Compose is resolved in an app without it. */
    private object Recomposers {
        // A Compose whose API has moved on would fail every step; it is taken as idle instead, as if it were absent.
        fun idle(): Boolean = try {
            Recomposer.runningRecomposers.value.none { it.hasPendingWork }
        } catch (_: LinkageError) {
            true
        }
    }
}
