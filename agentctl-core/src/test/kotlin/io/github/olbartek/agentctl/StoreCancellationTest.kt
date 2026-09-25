package io.github.olbartek.agentctl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class StoreCancellationTest {
    @Test
    fun matching() {
        val inner = ScopedId("sheet", "timer")
        assertTrue(cancellationMatches("timer", "timer"))
        assertTrue(cancellationMatches(EffectScope("sheet"), inner))
        assertFalse(cancellationMatches(EffectScope("other"), inner))
        // A child's cancel, scoped by its parent, reaches the child's effects under that parent only.
        assertTrue(cancellationMatches(ScopedId("element#0", EffectScope("sheet")), ScopedId("element#0", inner)))
        assertFalse(cancellationMatches(ScopedId("element#1", EffectScope("sheet")), ScopedId("element#0", inner)))
        assertFalse(cancellationMatches(ScopedId("element#0", EffectScope("sheet")), ScopedId("element#0", ScopedId("other", "timer"))))
        // An outer scope cancels everything nested in it.
        assertTrue(cancellationMatches(EffectScope("element#0"), ScopedId("element#0", inner)))
        assertFalse(cancellationMatches(ScopedId("element#0", null), ScopedId("element#0", inner)))
    }

    @Test
    fun aNestedScopeCancelStopsOnlyItsEffects() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val forever = Effect.run<String> { awaitCancellation() }
        val store = Store(Unit, Reducer<Unit, String> { state, action ->
            when (action) {
                "start" -> next(state, Effect.merge(forever.scoped("sheet").scoped("element#0"), forever.scoped("sheet").scoped("element#1")))
                "dismiss" -> next(state, Effect.cancel(EffectScope("sheet")).scoped("element#0"))
                else -> next(state)
            }
        }, scope)
        store.send("start")
        yield()
        assertEquals(2, store.effectsInFlight)
        store.send("dismiss")
        yield()
        assertEquals(1, store.effectsInFlight)
        scope.cancel()
    }
}
