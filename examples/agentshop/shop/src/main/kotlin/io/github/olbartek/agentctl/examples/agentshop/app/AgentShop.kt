package io.github.olbartek.agentctl.examples.agentshop.app

import io.github.olbartek.agentctl.AgentClock
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.Store
import io.github.olbartek.agentctl.examples.agentshop.clients.AccountBackend
import io.github.olbartek.agentctl.examples.agentshop.clients.AccountClient
import io.github.olbartek.agentctl.examples.agentshop.clients.AuthBackend
import io.github.olbartek.agentctl.examples.agentshop.clients.AuthClient
import io.github.olbartek.agentctl.examples.agentshop.clients.CartClient
import io.github.olbartek.agentctl.examples.agentshop.clients.CatalogClient
import io.github.olbartek.agentctl.examples.agentshop.clients.OrdersBackend
import io.github.olbartek.agentctl.examples.agentshop.clients.OrdersClient
import io.github.olbartek.agentctl.examples.agentshop.clients.SessionClient
import io.github.olbartek.agentctl.examples.agentshop.clients.SessionStorage
import io.github.olbartek.agentctl.examples.agentshop.models.ScheduledFaults
import io.github.olbartek.agentctl.examples.agentshop.models.ShopCalls
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/**
 * Everything AgentShop's reducers take from the outside world: the Kotlin counterpart of the reference's TCA
 * `@Dependency` values, passed explicitly to the reducer factories. Nothing in the app reads time, randomness,
 * identifiers or the locale except through these.
 */
class ShopDependencies(
    /** Timers (`resendIn` countdowns) sleep on it, and a placed order is dated with its `now()`. */
    val clock: AgentClock,
    /** Identifies each signed-in home. */
    val uuids: () -> UUID,
    val authClient: AuthClient = AuthClient(),
    val sessionClient: SessionClient = SessionClient(),
    val ordersClient: OrdersClient = OrdersClient(),
    val accountClient: AccountClient = AccountClient(),
    val catalogClient: CatalogClient = CatalogClient(),
    val cartClient: CartClient = CartClient(),
) {
    companion object {
        /**
         * The mocked clients on fresh in-memory backends, on `environment`'s clock, identifiers and mock backend.
         *
         * @param sessionStorage where the session is kept: in memory headlessly, a file in the app.
         * @param scheduledFaults failures a UI test schedules at launch (`mock-fault`); empty otherwise.
         * @param uptime the time elapsed since some fixed origin, which the auth server measures codes' age on. The
         *   headless host passes its virtual clock, which `advance` moves; the default reads `environment.clock.now()`,
         *   which advances with real time in the app.
         */
        fun mocked(
            environment: AgentEnvironment,
            sessionStorage: SessionStorage,
            scheduledFaults: ScheduledFaults = ScheduledFaults(),
            uptime: () -> Duration = uptime(environment.clock),
        ): ShopDependencies {
            val calls = ShopCalls(environment.mocks, scheduledFaults)
            return ShopDependencies(
                clock = environment.clock,
                uuids = environment.uuids,
                authClient = AuthClient.live(calls, AuthBackend(uptime)),
                sessionClient = SessionClient.live(calls, sessionStorage),
                ordersClient = OrdersClient.live(calls, sessionStorage, OrdersBackend(), environment.clock),
                accountClient = AccountClient.live(calls, sessionStorage, AccountBackend()),
                catalogClient = CatalogClient.live(calls),
                cartClient = CartClient.live(calls),
            )
        }

        /** Time since the first read, on `clock`'s `now()`. */
        fun uptime(clock: AgentClock): () -> Duration {
            val start = clock.now()
            return { java.time.Duration.between(start, clock.now()).toKotlinDuration() }
        }
    }
}

/** AgentShop's store, for the app (debug or release), the headless host and the live host alike. */
object AgentShop {
    /** The store on any environment, with the mocked clients: see [ShopDependencies.mocked] for the parameters. */
    fun store(
        environment: AgentEnvironment,
        sessionStorage: SessionStorage,
        scheduledFaults: ScheduledFaults = ScheduledFaults(),
        uptime: () -> Duration = ShopDependencies.uptime(environment.clock),
    ): Store<AppFeature.State, AppFeature.Action> = store(
        environment,
        ShopDependencies.mocked(environment, sessionStorage, scheduledFaults, uptime),
    )

    /** The store with explicit dependencies, for tests that stub a client. It starts on the launching screen. */
    fun store(environment: AgentEnvironment, dependencies: ShopDependencies): Store<AppFeature.State, AppFeature.Action> =
        Store(initialState = AppFeature.State.Launching, reducer = AppFeature.reducer(dependencies), scope = environment.scope)
}
