package io.github.olbartek.agentctl

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What AgentCtl drives: a state it can read, actions it can send, and how many effects are still running.
 *
 * [Store] is AgentCtl's own implementation, a small unidirectional store in the spirit of the reference's TCA
 * store. An app built on another architecture implements this over its own (a `ViewModel`'s `StateFlow`, say) and
 * counts the coroutines it launched for [effectsInFlight].
 */
public interface AgentStore<S, A> {
    /** The state over time, for the UI to render (`collectAsState()` in Compose). */
    public val states: StateFlow<S>

    /** The current state. */
    public val state: S get() = states.value

    /** Sends an action. Called on the store's own thread (the main thread in the app). */
    public fun send(action: A)

    /**
     * Effects started and not yet finished. Headless settling waits for this count to stop changing, along with
     * the state and the call log. It is not `pending`, which counts only sleeps on the clock.
     */
    public val effectsInFlight: Int
}

/** Turns a state and an action into the next state and the work to do next. Pure: no I/O, no time. */
public fun interface Reducer<S, A> {
    public fun reduce(state: S, action: A): Next<S, A>
}

/** A reducer's result: the new state and an [Effect]. */
public class Next<out S, out A>(public val state: S, public val effect: Effect<A> = Effect.None)

/** The next state, with no effect. */
public fun <S> next(state: S): Next<S, Nothing> = Next(state, Effect.None)

/** The next state and an effect. */
public fun <S, A> next(state: S, effect: Effect<A>): Next<S, A> = Next(state, effect)

/** Work a reducer asks the store to do after it returns. */
public sealed class Effect<out A> {
    /** Nothing to do. */
    public data object None : Effect<Nothing>()

    /** Send another action right after this one, before any other effect runs. */
    public data class Send<A>(val action: A) : Effect<A>()

    /**
     * Run `block` in the store's scope, sending actions back with its `send`. With an [id], a later
     * [Cancel] (or a run with the same id and `cancelInFlight`) cancels it.
     */
    public class Run<A>(
        public val id: Any? = null,
        public val cancelInFlight: Boolean = false,
        public val block: suspend (send: suspend (A) -> Unit) -> Unit,
    ) : Effect<A>()

    /** Cancel the running effects started with [id]. */
    public data class Cancel(val id: Any) : Effect<Nothing>()

    /** Several effects, started in order. */
    public data class Merge<A>(val effects: List<Effect<A>>) : Effect<A>()

    /** Lifts this effect's actions into a parent's action type, for a parent reducer that embeds a child. */
    public fun <P> map(embed: (A) -> P): Effect<P> = when (this) {
        None -> None
        is Send -> Send(embed(action))
        is Run -> Run(id, cancelInFlight) { send -> block { action -> send(embed(action)) } }
        is Cancel -> this
        is Merge -> Merge(effects.map { it.map(embed) })
    }

    /** This effect, cancellable by [id]; `cancelInFlight` cancels a running one with the same id first. */
    public fun cancellable(id: Any, cancelInFlight: Boolean = false): Effect<A> = when (this) {
        is Run -> Run(id, cancelInFlight, block)
        is Merge -> Merge(effects.map { it.cancellable(id, cancelInFlight) })
        else -> this
    }

    /**
     * This effect with every cancellation id moved into `scope` — a stack element's id, say — so that equal ids
     * in two instances of one screen never cancel each other, and `Effect.cancel(EffectScope(scope))` cancels all of
     * them at once (as a container does when it pops the element).
     */
    public fun scoped(scope: Any): Effect<A> = when (this) {
        None, is Send -> this
        is Run -> Run(ScopedId(scope, id), cancelInFlight && id != null, block)
        is Cancel -> Cancel(ScopedId(scope, id))
        is Merge -> Merge(effects.map { it.scoped(scope) })
    }

    public companion object {
        public fun <A> run(block: suspend (send: suspend (A) -> Unit) -> Unit): Effect<A> = Run(block = block)

        public fun <A> send(action: A): Effect<A> = Send(action)

        public fun cancel(id: Any): Effect<Nothing> = Cancel(id)

        public fun <A> merge(vararg effects: Effect<A>): Effect<A> = Merge(effects.toList())
    }
}

/** A cancellation id inside a scope: see [Effect.scoped]. */
public data class ScopedId(val scope: Any, val id: Any?)

/** Cancels every effect whose id was scoped to [scope] with [Effect.scoped]: `Effect.cancel(EffectScope(id))`. */
public data class EffectScope(val scope: Any)

/**
 * Whether `Effect.cancel(cancelId)` cancels an effect running under [effectId]: the same id, or an
 * [EffectScope] whose scope the effect's id was scoped to. Nesting is followed: once a child's
 * `Cancel(EffectScope(inner))` is itself scoped by its parent, it becomes
 * `ScopedId(outer, EffectScope(inner))` and cancels `ScopedId(outer, ScopedId(inner, …))`, so a
 * container nested in another still cancels only its own children's effects.
 */
public fun cancellationMatches(cancelId: Any, effectId: Any): Boolean = when {
    cancelId == effectId -> true
    effectId !is ScopedId -> false
    cancelId is EffectScope -> effectId.scope == cancelId.scope
    cancelId is ScopedId -> cancelId.scope == effectId.scope && cancelId.id != null && effectId.id != null &&
        cancellationMatches(cancelId.id, effectId.id)
    else -> false
}

/**
 * Embeds a child reducer in a parent's state and actions. `extract` returns the child's action for a parent
 * action meant for the child, or `null` for any other, which the child never sees.
 */
public fun <PS, PA, CS, CA> Reducer<CS, CA>.pullback(
    get: (PS) -> CS,
    set: (PS, CS) -> PS,
    extract: (PA) -> CA?,
    embed: (CA) -> PA,
): Reducer<PS, PA> = Reducer { state, action ->
    val childAction = extract(action) ?: return@Reducer next(state)
    val result = reduce(get(state), childAction)
    next(set(state, result.state), result.effect.map(embed))
}

/** Runs each reducer in turn on the state the previous one returned, and merges their effects. */
public fun <S, A> combine(vararg reducers: Reducer<S, A>): Reducer<S, A> = Reducer { state, action ->
    var current = state
    val effects = mutableListOf<Effect<A>>()
    for (reducer in reducers) {
        val result = reducer.reduce(current, action)
        current = result.state
        if (result.effect != Effect.None) effects.add(result.effect)
    }
    Next(current, if (effects.isEmpty()) Effect.None else Effect.Merge(effects))
}

/**
 * AgentCtl's store: state in a [StateFlow], a pure [Reducer], and effects launched in [scope] — the headless
 * host's virtual-time scope, or the main thread in the app.
 *
 * Actions sent while one is being reduced (by [Effect.Send], or re-entrantly) queue and run in order. Not
 * thread-safe: send from the scope's own dispatcher, which every effect's `send` does for you.
 */
public class Store<S, A>(
    initialState: S,
    private val reducer: Reducer<S, A>,
    private val scope: CoroutineScope,
) : AgentStore<S, A> {
    private val mutableState = MutableStateFlow(initialState)
    private val queue = ArrayDeque<A>()
    private var reducing = false
    private val running = AtomicInteger(0)
    private val cancellable = mutableMapOf<Any, MutableSet<Job>>()

    override val states: StateFlow<S> = mutableState.asStateFlow()

    override val effectsInFlight: Int get() = running.get()

    override fun send(action: A) {
        queue.addLast(action)
        if (reducing) return
        reducing = true
        try {
            while (queue.isNotEmpty()) {
                val result = reducer.reduce(mutableState.value, queue.removeFirst())
                mutableState.value = result.state
                start(result.effect)
            }
        } finally {
            reducing = false
        }
    }

    private fun start(effect: Effect<A>) {
        when (effect) {
            Effect.None -> Unit
            is Effect.Send -> queue.addLast(effect.action)
            is Effect.Cancel -> cancel(effect.id)
            is Effect.Merge -> effect.effects.forEach(::start)
            is Effect.Run -> launch(effect)
        }
    }

    private fun cancel(id: Any) {
        val keys = cancellable.keys.filter { cancellationMatches(id, it) }
        keys.flatMap { cancellable.remove(it)?.toList() ?: emptyList() }.forEach { it.cancel() }
    }

    private fun launch(effect: Effect.Run<A>) {
        val id = effect.id
        if (id != null && effect.cancelInFlight) cancel(id)
        val dispatcher = scope.coroutineContext[ContinuationInterceptor]
        running.incrementAndGet()
        val job = scope.launch {
            effect.block { action ->
                // Back on the store's dispatcher, whatever context the effect switched to.
                if (dispatcher != null) withContext(dispatcher) { send(action) } else send(action)
            }
        }
        // On completion rather than in a `finally`: an effect cancelled before it was dispatched never runs its
        // body, but it always completes.
        job.invokeOnCompletion { running.decrementAndGet() }
        if (id != null && job.isActive) {
            val jobs = cancellable.getOrPut(id) { mutableSetOf() }
            jobs.add(job)
            job.invokeOnCompletion { cancellable[id]?.remove(job) }
        }
    }
}
