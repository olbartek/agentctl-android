package io.github.olbartek.agentctl.examples.tinyapp

import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.DocsText
import io.github.olbartek.agentctl.MockLatency
import io.github.olbartek.agentctl.MockMethod
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.Store
import io.github.olbartek.agentctl.runtime.AppCheck
import io.github.olbartek.agentctl.runtime.AppCtlConfig
import io.github.olbartek.agentctl.runtime.GradleTasks
import io.github.olbartek.agentctl.runtime.HeadlessHost
import io.github.olbartek.agentctl.runtime.HelpExamples
import io.github.olbartek.agentctl.runtime.LiveHost
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Everything AgentCtl needs to know about TinyApp: the facts the CLI would otherwise hard-code, the data the docs
 * are rendered from, and the two functions that build the app's store.
 *
 * This is the whole integration. A host app writes one object like this, hands its [appCtl] to
 * `AgentCtl.run(config, args)` in its own executable (see `examples/tinyctl`) and to `AgentLaunch(config)` in its
 * debug app.
 */
object TinyAppConfig {
    /** Every screen an agent can reach, for `screens` and the generated docs. */
    val screens: List<ScreenDoc> get() = TinyRootAgent.registry

    /** Every method `mock <method> <error>` accepts. The runner does not know the app's clients, so the config tells it. */
    val mockMethods: List<MockMethod> get() = ItemsClient.mockMethods

    /** The prose around the generated command reference. Everything else in that document comes from the registry. */
    val docsText = DocsText(
        title = "TinyApp agent commands",
        intro = "TinyApp is the example app in this repository: a list, a detail screen and one mocked client.\n" +
            "Every screen is driven with the same commands, headlessly (`./tinyctl run \"…\"`) or from a\n" +
            "scenario file (`examples/tinyapp/scenarios/*.appctl`).",
        usageExamples = listOf(
            "./tinyctl run \"open 2; save\"                       # save the second item",
            "./tinyctl run \"open 2; save; advance 3s\"            # let the cooldown run out",
            "./tinyctl run \"mock items.fetch network; refresh\"   # make the next fetch fail",
            "./tinyctl test                                     # run every scenario",
        ),
        invocation = "./tinyctl",
        exampleCommand = "open 2",
        exampleStep = "screen=items/2 title=\"Second item\" saved=false cooldown=0",
        scenariosGlob = "examples/tinyapp/scenarios/*.appctl",
        mockExample = "mock items.fetch network",
        appendix = listOf(
            "## The example's data",
            "",
            "`items.fetch` always returns three items: `First item`, `Second item` and `Third item`.",
            "A `save` starts a ${ItemDetail.COOLDOWN_SECONDS}-second cooldown; saving again before it runs out reports",
            "`error=cooldown`. Headlessly, `advance ${ItemDetail.COOLDOWN_SECONDS}s` runs the countdown out at once.",
            "`open <id>` with an id the list does not have reports `error=notFound` instead of doing nothing.",
        ),
    )

    /**
     * The value `tinyctl` runs on, and the one `examples/tinyapp-android` hands its agent bridge.
     *
     * Headlessly — `run`, `state`, `screens`, `docs`, `test` and a plain `check` — nothing but the JVM is needed. The
     * `app` commands and `check --ui` install `examples/tinyapp-android` on a device or emulator; TinyApp has no
     * screenshot tests, so the L3 rung of `check --ui` reports that it has nothing to run.
     */
    val appCtl: AppCtlConfig<TinyRoot.State, TinyRoot.Action>
        get() = AppCtlConfig(
            name = "TinyApp",
            // The file the CLI walks up the tree for; `scenariosPath` and `docsPath` are resolved against its directory.
            rootMarker = "settings.gradle.kts",
            // examples/tinyapp-android, which `app launch` builds, installs and starts with its launch extras.
            applicationId = "io.github.olbartek.agentctl.examples.tinyapp",
            launchActivity = ".android.MainActivity",
            // `check` runs these from the root: L0 builds every module of this repository, L1 runs every test.
            gradle = GradleTasks(
                build = listOf("assemble"),
                test = listOf("test"),
                install = ":examples:tinyapp-android:installDebug",
            ),
            scenariosPath = "examples/tinyapp/scenarios",
            // The app is not at the root of its repository, so its command reference lives beside it.
            docsPath = "examples/tinyapp/agent-commands.md",
            // L4 sends this scenario through the bridge. The others use `advance`, which a running app refuses.
            appCheck = AppCheck(scenario = "refresh-error", expectScreen = "items"),
            help = HelpExamples(
                invocation = "./tinyctl",
                note = "TinyApp is this repository's example app; everything but the app commands runs headlessly, with no device.",
                runScripts = listOf("open 2; save", "refresh", "expect screen=items items=3"),
                sessionPath = ".appctl/tiny.session",
                scenarioPath = "examples/tinyapp/scenarios/browse.appctl",
                appSeeds = listOf("open 2", "refresh; open 2"),
                appScripts = listOf("open 2; expect saved=false", "save"),
            ),
            mockMethods = mockMethods,
            docsText = docsText,
            screens = screens,
            makeHeadless = { headless() },
            makeLive = { latency, dispatcher -> live(latency, dispatcher) },
            // TinyApp keeps nothing between launches, so there is no saved session to forget.
            clearSession = {},
        )

    /** TinyApp's store on any environment: the headless host's, the live host's, or a release build's own. */
    fun store(environment: AgentEnvironment): Store<TinyRoot.State, TinyRoot.Action> = Store(
        initialState = TinyRoot.State(),
        reducer = TinyRoot.reducer(ItemsClient.mock(environment.mocks), environment.clock),
        scope = environment.scope,
    )

    /** A deterministic store: a virtual clock, a fixed date, and everything else [HeadlessHost] pins. */
    fun headless(): HeadlessHost<TinyRoot.State, TinyRoot.Action> = HeadlessHost(TinyRootAgent, mockMethods, ::store)

    /** The store the app would run in a debug build behind the bridge: real time and real mock latency. */
    fun live(latency: MockLatency, dispatcher: CoroutineDispatcher): LiveHost<TinyRoot.State, TinyRoot.Action> =
        LiveHost(TinyRootAgent, mockMethods, latency, dispatcher, ::store)
}
