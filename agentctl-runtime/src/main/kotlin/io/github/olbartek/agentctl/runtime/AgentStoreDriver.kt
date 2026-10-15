package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.ActiveScreen
import io.github.olbartek.agentctl.AgentContainer
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.AgentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * How a host builds a store app in two stages, so that `device relaunch` starts the app's state over while what its
 * clients stored stays (CONTRACT.md §2.4), as the reference rebuilds its store over the same dependencies.
 *
 * [prepare] runs once per host, on an environment whose `scope` is the host's: build the clients there, and anything
 * they keep (a session store, a cart on disk). It returns the function that builds the store on the scope it is given,
 * once at launch and again at each relaunch; the store runs its effects in that scope, which a relaunch cancels.
 *
 * ```kotlin
 * HeadlessHost(MyAppAgent, mockMethods, deviceConfig, AgentAppFactory { environment ->
 *     val clients = MyClients.mocked(environment.mocks, SessionStorage.inMemory())
 *     return@AgentAppFactory { scope -> MyApp.store(environment, clients, scope) }
 * })
 * ```
 *
 * A host given a plain `makeStore` instead runs all of it again at each relaunch, clients included.
 */
public fun interface AgentAppFactory<S, A> {
    /** Builds what outlives a relaunch, and returns how to build a store on a scope. */
    public fun prepare(environment: AgentEnvironment): (CoroutineScope) -> AgentStore<S, A>
}

/** A `makeStore` as an [AgentAppFactory]: everything is built again at each launch. */
internal fun <S, A> rebuilding(makeStore: (AgentEnvironment) -> AgentStore<S, A>): AgentAppFactory<S, A> =
    AgentAppFactory { environment -> { scope -> makeStore(environment.withScope(scope)) } }

/**
 * The [AgentDriver] of an app on a store: an [AgentStore] whose root an [AgentContainer] describes.
 *
 * Settling compares the root state and the effects in flight; `state` and `--diff` render the root state
 * ([StateDump]); a relaunch cancels the store's scope, which ends its effects, and builds a fresh store from the
 * initial state with the [AgentAppFactory].
 *
 * @param hostEnvironment the host's environment, whose `scope` outlives every launch.
 */
public class AgentStoreDriver<S, A>(
    /** The root's agent surface: the active screen and its commands, read from the state. */
    public val container: AgentContainer<S, A>,
    private val hostEnvironment: AgentEnvironment,
    app: AgentAppFactory<S, A>,
) : AgentDriver<A, AgentStore<S, A>> {
    private val hostScope = hostEnvironment.scope
    private val makeStore: (CoroutineScope) -> AgentStore<S, A> = app.prepare(hostEnvironment)

    /**
     * What the current store was built on: the host's environment with a scope of the store's own, which a relaunch
     * cancels, so the old store's effects end with it while everything else carries over.
     */
    public var environment: AgentEnvironment = hostEnvironment.withScope(childScope(hostScope))
        private set

    /** The store the app shows. A relaunch replaces it. */
    public var store: AgentStore<S, A> = makeStore(environment.scope)
        private set

    override val app: AgentStore<S, A> get() = store

    /** The root state. */
    public val state: S get() = store.state

    override var onRelaunch: (() -> Unit)? = null

    override fun activeScreen(): ActiveScreen<A> = container.activeScreen(store.state)

    override fun send(action: A): Unit = store.send(action)

    private data class Fingerprint(val state: Any?, val effects: Int)

    override fun fingerprint(): Any = Fingerprint(store.state, store.effectsInFlight)

    override fun relaunch() {
        environment.scope.cancel()
        environment = hostEnvironment.withScope(childScope(hostScope))
        store = makeStore(environment.scope)
        onRelaunch?.invoke()
    }

    override fun stateSnapshot(): Any? = store.state

    override fun stateDiff(before: Any?): String = StateDump.diff(before, store.state)

    override val stateDump: String get() = StateDump.render(store.state)
}
