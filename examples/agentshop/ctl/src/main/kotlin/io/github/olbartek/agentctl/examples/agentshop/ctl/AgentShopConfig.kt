package io.github.olbartek.agentctl.examples.agentshop.ctl

import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.DocsText
import io.github.olbartek.agentctl.MockLatency
import io.github.olbartek.agentctl.MockMethod
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.examples.agentshop.app.AgentShop
import io.github.olbartek.agentctl.examples.agentshop.app.AppFeature
import io.github.olbartek.agentctl.examples.agentshop.app.AppFeatureAgent
import io.github.olbartek.agentctl.examples.agentshop.clients.AccountClient
import io.github.olbartek.agentctl.examples.agentshop.clients.AuthClient
import io.github.olbartek.agentctl.examples.agentshop.clients.CartClient
import io.github.olbartek.agentctl.examples.agentshop.clients.CatalogClient
import io.github.olbartek.agentctl.examples.agentshop.clients.MockAccounts
import io.github.olbartek.agentctl.examples.agentshop.clients.OrdersClient
import io.github.olbartek.agentctl.examples.agentshop.clients.SessionStorage
import io.github.olbartek.agentctl.examples.agentshop.models.ScheduledFaults
import io.github.olbartek.agentctl.runtime.AppCheck
import io.github.olbartek.agentctl.runtime.AppCtlConfig
import io.github.olbartek.agentctl.runtime.GradleTasks
import io.github.olbartek.agentctl.runtime.HeadlessHost
import io.github.olbartek.agentctl.runtime.HelpExamples
import io.github.olbartek.agentctl.runtime.LiveHost
import io.github.olbartek.agentctl.runtime.VirtualTimeDispatcher
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Everything AgentCtl needs to know about AgentShop. `shopctl` runs on [appCtl]; the debug app hands its agent bridge
 * `appCtl(sessionStorage)` with the storage it keeps the session in.
 *
 * Its root is `examples/agentshop` (the wrapper sets `APPCTL_ROOT`), so `scenarios` and `agent-commands.md` are
 * resolved there, and the Gradle tasks below run through `examples/agentshop/gradlew`, which forwards to the
 * repository's wrapper.
 */
object AgentShopConfig {
    /**
     * How this example spells the CLI everywhere it names itself: the wrapper script beside the scenarios, which
     * rebuilds `shopctl` before running it. Never the bare executable, which is not on anyone's PATH.
     */
    private const val INVOCATION = "./appctl"

    /** Where the scenario files live, as a reader should type it. */
    private const val SCENARIOS_GLOB = "scenarios/*.appctl"

    /** Every screen AgentShop's agent layer can reach, for docs and `./appctl screens`. */
    val screens: List<ScreenDoc> get() = AppFeatureAgent.registry

    /** Every method `mock` can fail: the runner does not know AgentShop's clients, so the config tells it. */
    val mockMethods: List<MockMethod>
        get() = AuthClient.mockMethods + AccountClient.mockMethods + CatalogClient.mockMethods + CartClient.mockMethods +
            OrdersClient.mockMethods

    /** The host-written prose in `agent-commands.md`. */
    val docsText: DocsText = DocsText(
        title = "Agent commands",
        intro = "Every screen of AgentShop can be driven with the same commands headlessly (`$INVOCATION run \"…\"`),\n" +
            "in scenario files (`$SCENARIOS_GLOB`), at launch (`-appctl-seed`) and in the running app " +
            "(`$INVOCATION app run \"…\"`).",
        usageExamples = listOf(
            "$INVOCATION run \"login-as alice; open 1003; cancel\"              # cancel a pending order",
            "$INVOCATION run \"use-otp; email alice@example.com; send; advance 30s; resend\"",
            "$INVOCATION run \"mock orders.fetchOrders network; login-as alice; retry\"",
            "$INVOCATION run --session .appctl/s.session \"register\"   # keep going from the same state next time",
            "$INVOCATION app launch --no-build --seed \"login-as bob; tab profile\"   # the real app, already there",
        ),
        invocation = INVOCATION,
        // A real step from `./appctl run "…; submit"` on the login screen, so the syntax section shows AgentShop's own
        // output rather than `screen=<path> <key>=<value>`.
        exampleCommand = "submit",
        exampleStep = "screen=home/orders orders=3 loading=false calls=auth.login,session.save,orders.fetchOrders",
        scenariosGlob = SCENARIOS_GLOB,
        mockExample = "mock orders.fetchOrders network",
        appendix = listOf(
            "## Test accounts",
            "",
            "| Account | Password | Notes |",
            "|---|---|---|",
            "| `${MockAccounts.alice.user.email}` | `${MockAccounts.alice.password ?: ""}` | 3 orders: #1001 delivered, #1002 shipped, #1003 pending |",
            "| `${MockAccounts.bob.user.email}` | `${MockAccounts.bob.password ?: ""}` | no orders |",
            "| `${MockAccounts.locked.user.email}` | any | always `accountLocked` |",
            "",
            "The OTP code is always `${MockAccounts.OTP_CODE}` and the password-reset code is always `${MockAccounts.RESET_CODE}`.",
        ),
    )

    /**
     * The example commands in the CLI's own help pages, written in AgentShop's vocabulary: its accounts, its screen
     * paths and its scenario files, rather than the package's `<command>` placeholders.
     */
    val help: HelpExamples = HelpExamples(
        invocation = INVOCATION,
        note = "Always call it through the $INVOCATION wrapper in examples/agentshop, which rebuilds the CLI incrementally.",
        runScripts = listOf("login-as alice; open 1003; cancel", "email alice@example.com", "expect screen=auth/login"),
        scenarioPath = "scenarios/shop-order-cancel.appctl",
        appSeeds = listOf("login-as alice", "login-as bob; tab profile"),
        appScripts = listOf("open 1003; cancel; expect status=cancelled", "tab profile"),
    )

    /** What `shopctl` runs on: headlessly the session lives in memory, so there is nothing on disk to clear. */
    val appCtl: AppCtlConfig<AppFeature.State, AppFeature.Action> get() = appCtl(SessionStorage.inMemory())

    /**
     * The config the debug app hands its agent bridge: its live store keeps the session in `sessionStorage`, which
     * `clear-session` empties, and fails the calls `scheduledFaults` names (the `mock-fault` launch extras).
     */
    fun appCtl(
        sessionStorage: SessionStorage,
        scheduledFaults: ScheduledFaults = ScheduledFaults(),
    ): AppCtlConfig<AppFeature.State, AppFeature.Action> = AppCtlConfig(
        name = "AgentShop",
        // Without APPCTL_ROOT the CLI walks up to the directory that holds the wrapper: examples/agentshop.
        rootMarker = "appctl",
        applicationId = "io.github.olbartek.agentctl.examples.agentshop",
        launchActivity = ".app.MainActivity",
        // `check` runs these from examples/agentshop, through its gradlew (which forwards to the repository's).
        gradle = GradleTasks(
            build = listOf(":examples:agentshop:shop:assemble", ":examples:agentshop:ctl:assemble", ":examples:agentshop:shopctl:assemble"),
            test = listOf(":examples:agentshop:shop:test", ":examples:agentshop:ctl:test"),
            install = ":examples:agentshop:app:installDebug",
        ),
        scenariosPath = "scenarios",
        docsPath = "agent-commands.md",
        appCheck = AppCheck(seed = "login-as alice", scenario = "shop-order-cancel", expectScreen = "home/orders"),
        help = help,
        mockMethods = mockMethods,
        docsText = docsText,
        screens = screens,
        makeHeadless = { headless() },
        makeLive = { latency, dispatcher -> live(latency, dispatcher, sessionStorage, scheduledFaults) },
        clearSession = { sessionStorage.store(null) },
    )

    /**
     * A deterministic store: fresh in-memory backends and session, and the headless host's pinned environment. The
     * auth server measures codes' age on the host's virtual clock, so `advance 5m` expires them.
     */
    fun headless(): HeadlessHost<AppFeature.State, AppFeature.Action> = HeadlessHost(AppFeatureAgent, mockMethods) { environment ->
        AgentShop.store(environment, SessionStorage.inMemory(), uptime = virtualUptime(environment))
    }

    /**
     * The store the debug app runs behind its bridge: real time, real mock latency, the app's session storage and
     * the UI test's scheduled faults.
     */
    fun live(
        latency: MockLatency,
        dispatcher: CoroutineDispatcher,
        sessionStorage: SessionStorage,
        scheduledFaults: ScheduledFaults = ScheduledFaults(),
    ): LiveHost<AppFeature.State, AppFeature.Action> = LiveHost(AppFeatureAgent, mockMethods, latency, dispatcher) { environment ->
        AgentShop.store(environment, sessionStorage, scheduledFaults)
    }

    /** The headless host's virtual time, which only `advance` moves. */
    private fun virtualUptime(environment: AgentEnvironment): () -> Duration {
        val dispatcher = environment.scope.coroutineContext[ContinuationInterceptor] as VirtualTimeDispatcher
        return { dispatcher.currentTime.milliseconds }
    }
}
