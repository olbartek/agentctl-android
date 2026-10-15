package io.github.olbartek.agentctl.examples.tinymvvm

import io.github.olbartek.agentctl.AgentDevice
import io.github.olbartek.agentctl.AgentDeviceConfig
import io.github.olbartek.agentctl.AgentIntent
import io.github.olbartek.agentctl.DocsText
import io.github.olbartek.agentctl.MockLatency
import io.github.olbartek.agentctl.MockMethod
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.runtime.AgentViewModelDriver
import io.github.olbartek.agentctl.runtime.AppCtlConfig
import io.github.olbartek.agentctl.runtime.GradleTasks
import io.github.olbartek.agentctl.runtime.HeadlessHost
import io.github.olbartek.agentctl.runtime.HelpExamples
import io.github.olbartek.agentctl.runtime.LiveHost
import kotlinx.coroutines.CoroutineDispatcher

/**
 * TinyMVVM's integration: TinyApp rebuilt as MVVM — Jetpack ViewModels under a coordinator, no store — and driven
 * through a [AgentViewModelDriver]. It runs TinyApp's own scenario files and prints what TinyApp prints.
 */
object TinyMVVMConfig {
    val screens: List<ScreenDoc> get() = AppCoordinator.registry

    val mockMethods: List<MockMethod> get() = ItemsClient.mockMethods

    /** The same links and push as TinyApp's, as intents on the coordinator. */
    val device: AgentDeviceConfig<AgentIntent<AppCoordinator>>
        get() = AgentDeviceConfig(
            openUrl = { url ->
                val path = url.path?.split("/")?.filter { it.isNotEmpty() }
                // ASCII digits only, as Swift's `Int(_:)` reads them; `toIntOrNull` alone takes any Unicode digit.
                val id = path?.singleOrNull()?.takeIf { it.matches(Regex("[+-]?[0-9]+")) }?.toIntOrNull()
                if (url.scheme == "tinyapp" && url.host == "items" && id != null) AgentIntent { it.openItem(id) } else null
            },
            pushes = listOf(
                AgentDevice.Push(
                    "item-updated",
                    help = "Item 2 was updated.",
                    received = AgentIntent { it.items.refresh() },
                    opened = AgentIntent { it.openItem(2) },
                ),
            ),
        )

    val docsText = DocsText(
        title = "TinyMVVM agent commands",
        intro = "TinyMVVM is TinyApp rebuilt as MVVM: Jetpack ViewModels under a coordinator, no store.\n" +
            "It is driven with the same commands, and runs TinyApp's scenario files (`examples/tinyapp/scenarios/*.appctl`).",
        usageExamples = listOf(
            "./tinymvvmctl run \"open 2; save\"                       # save the second item",
            "./tinymvvmctl test                                     # TinyApp's scenarios, on the MVVM app",
        ),
        invocation = "./tinymvvmctl",
        exampleCommand = "open 2",
        exampleStep = "screen=items/2 title=\"Second item\" saved=false cooldown=0",
        scenariosGlob = "examples/tinyapp/scenarios/*.appctl",
        mockExample = "mock items.fetch network",
        appendix = emptyList(),
    )

    /** The value `tinymvvmctl` runs on. TinyMVVM has no Android app: everything runs headlessly, with no device. */
    val appCtl: AppCtlConfig<AgentViewModelDriver<AppCoordinator>, AgentIntent<AppCoordinator>>
        get() = AppCtlConfig(
            name = "TinyMVVM",
            rootMarker = "settings.gradle.kts",
            applicationId = "io.github.olbartek.agentctl.examples.tinymvvm",
            gradle = GradleTasks(
                build = listOf(":examples:tinymvvmctl:assemble"),
                test = listOf(":tests:test"),
                install = "<none: TinyMVVM has no Android app>",
            ),
            // TinyApp's scenarios: the point of this example is that they pass unchanged.
            scenariosPath = "examples/tinyapp/scenarios",
            docsPath = "examples/tinymvvm/agent-commands.md",
            help = HelpExamples(
                invocation = "./tinymvvmctl",
                note = "TinyMVVM is TinyApp as MVVM; it runs headlessly, with no device.",
                runScripts = listOf("open 2; save", "refresh", "expect screen=items items=3"),
                sessionPath = ".appctl/tinymvvm.session",
                scenarioPath = "examples/tinyapp/scenarios/browse.appctl",
                appSeeds = listOf("open 2", "refresh; open 2"),
                appScripts = listOf("open 2; expect saved=false", "save"),
            ),
            mockMethods = mockMethods,
            deviceConfig = device,
            docsText = docsText,
            screens = screens,
            makeHeadless = { headless() },
            makeLive = { latency, dispatcher -> live(latency, dispatcher) },
            clearSession = {},
        )

    /** A deterministic app on the JVM: a virtual clock, a fixed date, and everything else [HeadlessHost] pins. */
    fun headless(
        device: AgentDeviceConfig<AgentIntent<AppCoordinator>> = this.device,
    ): HeadlessHost<AgentViewModelDriver<AppCoordinator>, AgentIntent<AppCoordinator>> =
        HeadlessHost(mockMethods, device) { AppCoordinator(it) }

    /** The app as it would run behind the bridge in a debug build: real time and real mock latency. */
    fun live(
        latency: MockLatency,
        dispatcher: CoroutineDispatcher,
    ): LiveHost<AgentViewModelDriver<AppCoordinator>, AgentIntent<AppCoordinator>> =
        LiveHost(mockMethods, latency, dispatcher, device) { AppCoordinator(it) }
}
