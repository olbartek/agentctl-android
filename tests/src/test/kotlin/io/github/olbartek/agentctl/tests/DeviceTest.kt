package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentContainer
import io.github.olbartek.agentctl.AgentDeviceConfig
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.AgentPush
import io.github.olbartek.agentctl.AgentRegistry
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.BiometryResult
import io.github.olbartek.agentctl.DeviceLocation
import io.github.olbartek.agentctl.DevicePhase
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.MockMethod
import io.github.olbartek.agentctl.PermissionStatus
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.ScreenDoc
import io.github.olbartek.agentctl.StepFormatter
import io.github.olbartek.agentctl.Store
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.next
import io.github.olbartek.agentctl.runtime.AppFactory
import io.github.olbartek.agentctl.runtime.HeadlessHost
import io.github.olbartek.agentctl.runtime.RunStatus
import io.github.olbartek.agentctl.testsupport.AgentCoverage
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A one-screen app that uses every part of the device: a camera permission, biometry, connectivity and location it
 * observes, the scene phase, links, a push and a mocked call.
 */
object Gadget {
    data class State(
        val camera: String = "unasked",
        val faceID: String = "none",
        val online: String = "unknown",
        val location: String = "unknown",
        val phase: String = "none",
        val link: String = "none",
        val pings: Int = 0,
        val fetched: String = "none",
    )

    sealed interface Action {
        data object OnAppear : Action
        data object CameraTapped : Action
        data class CameraAnswered(val status: PermissionStatus) : Action
        data object UnlockTapped : Action
        data class Unlocked(val result: BiometryResult) : Action
        data class OnlineChanged(val online: Boolean) : Action
        data class LocationChanged(val location: DeviceLocation?) : Action
        data class PhaseChanged(val phase: DevicePhase) : Action
        data class LinkOpened(val link: String) : Action
        data object Pinged : Action
        data object FetchTapped : Action
        data class Fetched(val result: String) : Action
    }

    class GadgetError(val code: String) : Exception(code)

    fun reducer(environment: AgentEnvironment) = Reducer<State, Action> { state, action ->
        val device = environment.device
        when (action) {
            Action.OnAppear -> next(
                state,
                Effect.merge(
                    Effect.run { send -> device.onlineUpdates().collect { send(Action.OnlineChanged(it)) } },
                    Effect.run { send -> device.locationUpdates().collect { send(Action.LocationChanged(it)) } },
                ),
            )
            Action.CameraTapped -> next(state, Effect.run { send -> send(Action.CameraAnswered(device.requestPermission("camera"))) })
            is Action.CameraAnswered -> next(state.copy(camera = action.status.rawValue))
            Action.UnlockTapped -> next(state, Effect.run { send -> send(Action.Unlocked(device.authenticateBiometry())) })
            is Action.Unlocked -> next(state.copy(faceID = action.result.name.lowercase()))
            is Action.OnlineChanged -> next(state.copy(online = if (action.online) "yes" else "no"))
            is Action.LocationChanged -> next(state.copy(location = action.location?.let { "${it.latitude},${it.longitude}" } ?: "none"))
            is Action.PhaseChanged -> next(state.copy(phase = action.phase.rawValue))
            is Action.LinkOpened -> next(state.copy(link = action.link))
            Action.Pinged -> next(state.copy(pings = state.pings + 1))
            Action.FetchTapped -> next(
                state,
                Effect.run { send ->
                    try {
                        environment.mocks.call("gadget.fetch", ::GadgetError) {}
                        send(Action.Fetched("ok"))
                    } catch (error: GadgetError) {
                        send(Action.Fetched(error.code))
                    }
                },
            )
            is Action.Fetched -> next(state.copy(fetched = action.result))
        }
    }

    object Screen : AgentScreen<State, Action>, AgentContainer<State, Action> {
        override val screenName: String = "Gadget"
        override val screenPaths: List<String> = listOf("gadget")

        override fun screenPath(state: State): String = "gadget"

        override val summaryKeys: List<String> = listOf("camera", "faceID", "online", "location", "phase", "link", "pings", "fetched")

        override fun summary(state: State): List<SummaryItem> = listOf(
            SummaryItem("camera", state.camera), SummaryItem("faceID", state.faceID), SummaryItem("online", state.online),
            SummaryItem("location", state.location), SummaryItem("phase", state.phase), SummaryItem("link", state.link),
            SummaryItem("pings", state.pings.toString()), SummaryItem("fetched", state.fetched),
        )

        override val onAppear: Action = Action.OnAppear

        override val commands: List<AgentCommand<State, Action>> = listOf(
            AgentCommand.action("camera", "Ask for the camera.", Action.CameraTapped),
            AgentCommand.action("unlock", "Unlock with Face ID.", Action.UnlockTapped),
            AgentCommand.action("fetch", "Fetch.", Action.FetchTapped),
        )

        override val registry: List<ScreenDoc> get() = screenDocs
    }

    val device = AgentDeviceConfig<Action>(
        permissions = listOf("camera", "location"),
        openUrl = { if (it.scheme == "gadget") Action.LinkOpened(it.toString()) else null },
        pushes = listOf(AgentPush("ping", help = "A ping.", received = Action.Pinged, opened = Action.LinkOpened("ping"))),
        scenePhase = { Action.PhaseChanged(it) },
        relaunch = true,
        offlineErrorCode = "offline",
        location = true,
        biometry = true,
    )

    fun host(device: AgentDeviceConfig<Action> = Gadget.device) = HeadlessHost(
        Screen,
        listOf(MockMethod("gadget.fetch", listOf("offline", "boom"))),
        device,
    ) { environment -> Store(State(), reducer(environment), environment.scope) }
}

/** A notebook whose client keeps the last note written: what a relaunch keeps, or not, depending on the host. */
object Notebook {
    data class State(val note: String = "none")

    sealed interface Action {
        data object OnAppear : Action
        data class Write(val text: String) : Action
    }

    /** The app's client: what it stores outlives the store. */
    class Notes {
        var last: String? = null
    }

    fun store(notes: Notes, scope: kotlinx.coroutines.CoroutineScope) = Store(
        State(),
        Reducer<State, Action> { state, action ->
            when (action) {
                Action.OnAppear -> next(state.copy(note = notes.last ?: "none"))
                is Action.Write -> {
                    notes.last = action.text
                    next(state.copy(note = action.text))
                }
            }
        },
        scope,
    )

    object Screen : AgentScreen<State, Action>, AgentContainer<State, Action> {
        override val screenPaths: List<String> = listOf("notebook")

        override fun screenPath(state: State): String = "notebook"

        override val summaryKeys: List<String> = listOf("note")

        override fun summary(state: State): List<SummaryItem> = listOf(SummaryItem("note", state.note))

        override val onAppear: Action = Action.OnAppear

        override val commands: List<AgentCommand<State, Action>> =
            listOf(AgentCommand.text("write", "Write a note.") { Action.Write(it) })

        override val registry: List<ScreenDoc> get() = screenDocs
    }

    private val relaunch = AgentDeviceConfig<Action>(relaunch = true)

    /** Builds its client once per host: a relaunch keeps it. */
    fun preparedHost() = HeadlessHost(
        Screen, emptyList(), relaunch,
        AppFactory { _ ->
            val notes = Notes()
            return@AppFactory { scope -> store(notes, scope) }
        },
    )

    /** Builds its client inside `makeStore`: a relaunch builds a new one. */
    fun rebuildingHost() = HeadlessHost(Screen, emptyList(), relaunch) { environment -> store(Notes(), environment.scope) }
}

/** What this guards: the `device` runtime command (CONTRACT.md §2.4). Its texts are agentctl-ios's, byte for byte. */
class DeviceTest {
    /** The steps of `script` after `(launch)`, as text. */
    private fun run(script: String, host: HeadlessHost<Gadget.State, Gadget.Action> = Gadget.host()): Pair<String, RunStatus> =
        runBlocking {
            val runner = host.makeRunner()
            runner.launch()
            val result = runner.run(script)
            StepFormatter.text(result.steps) to result.status
        }

    private val start = "camera=unasked faceID=none online=yes location=none phase=none link=none pings=0 fetched=none"

    @Test
    fun aPermissionRequestShowsAPromptUntilAnswered() {
        val (text, status) = run("camera; expect prompt=camera; device prompt allow; expect prompt=none camera=granted; camera")
        assertEquals(RunStatus.OK, status)
        assertEquals(
            """
            > camera
              screen=gadget $start prompt=camera
            > expect prompt=camera
              screen=gadget $start prompt=camera
            > device prompt allow
              screen=gadget camera=granted faceID=none online=yes location=none phase=none link=none pings=0 fetched=none
            > expect prompt=none camera=granted
              screen=gadget camera=granted faceID=none online=yes location=none phase=none link=none pings=0 fetched=none
            > camera
              screen=gadget camera=granted faceID=none online=yes location=none phase=none link=none pings=0 fetched=none
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun aSetPermissionAnswersWithoutAPrompt() {
        val (text, _) = run("device permission camera denied; camera; expect camera=denied prompt=none")
        assertTrue("> camera\n  screen=gadget camera=denied" in text, text)
        assertFalse(" prompt=camera" in text, text)
    }

    @Test
    fun promptsQueueAndLimitedIsAnAnswer() {
        val (text, status) = run(
            "camera; camera; device prompt limited; expect prompt=camera camera=limited; device prompt deny; expect prompt=none camera=denied",
        )
        assertEquals(RunStatus.OK, status, text)
    }

    @Test
    fun permissionAndPromptErrors() {
        val cases = listOf(
            Triple(
                "device permission camera", RunStatus.USAGE,
                "usage: device permission <service> <not-determined|granted|denied|limited>; services: camera, location",
            ),
            Triple(
                "device permission camera maybe", RunStatus.USAGE,
                "usage: device permission <service> <not-determined|granted|denied|limited>; services: camera, location",
            ),
            Triple("device permission mic granted", RunStatus.FAILED, "unknown permission 'mic'; services: camera, location"),
            Triple(
                "camera; device permission camera granted", RunStatus.FAILED,
                "device permission: a prompt for camera is pending; answer it with device prompt allow|deny|limited",
            ),
            Triple("device prompt allow", RunStatus.FAILED, "device prompt: no prompt is pending"),
            Triple("device prompt yes", RunStatus.USAGE, "usage: device prompt <allow|deny|limited>"),
            Triple(
                "unlock; device prompt allow", RunStatus.FAILED,
                "device prompt: the pending prompt is biometry; answer it with device biometry match|fail",
            ),
        )
        for ((script, expected, message) in cases) {
            val (text, status) = run(script)
            assertEquals(expected, status, script)
            assertTrue(text.endsWith("  FAIL $message"), "$script:\n$text")
        }
    }

    @Test
    fun commonErrors() {
        val available = "permission, prompt, open-url, push, background, foreground, relaunch, network, location, biometry"
        val location = "usage: device location <lat>,<lon> or none, e.g. 52.2297,21.0122"
        val cases = listOf(
            "device" to "usage: device <subcommand>; available: $available",
            "device warp" to "unknown device command 'warp'; available: $available",
            "device background now" to "usage: device background",
            "device relaunch x" to "usage: device relaunch",
            "device network slow" to "usage: device network <online|offline>",
            "device location 52.2;21" to location,
            "device location 91,0" to location,
            "device location 1e2,0" to location,
            "device location \"52.2, 21\"" to location,
            "device biometry maybe" to "usage: device biometry <enrolled|unenrolled|match|fail>",
            "device open-url nope" to "usage: device open-url <url>",
            "device push ping later" to "usage: device push <name> [tap]; pushes: ping",
        )
        for ((script, message) in cases) {
            val (text, status) = run(script)
            assertEquals(RunStatus.USAGE, status, script)
            assertTrue(text.endsWith("  FAIL $message"), "$script:\n$text")
        }
    }

    @Test
    fun aSubcommandTheAppDidNotConfigure() {
        val (text, status) = run("device push ping", Gadget.host(AgentDeviceConfig(openUrl = { null })))
        assertEquals(RunStatus.USAGE, status)
        assertTrue(text.endsWith("  FAIL device push is not configured by this app; available: open-url"), text)
    }

    @Test
    fun noDeviceConfigurationMeansNoDeviceCommand() {
        val (text, status) = run("device network offline", Gadget.host(AgentDeviceConfig.none()))
        assertEquals(RunStatus.FAILED, status)
        assertTrue(text.endsWith("  FAIL unknown command 'device' on gadget. Valid here: camera, unlock, fetch, expect, advance, mock"), text)
        val (configured, _) = run("nope")
        assertTrue(configured.endsWith("Valid here: camera, unlock, fetch, expect, advance, mock, device"), configured)
    }

    @Test
    fun linksAndPushes() {
        val (text, status) = run(
            "device open-url gadget://x/1; expect link=gadget://x/1; device push ping; expect pings=1 link=gadget://x/1; " +
                "device push ping tap; expect pings=1 link=ping",
        )
        assertEquals(RunStatus.OK, status, text)
        val (unhandled, failed) = run("device open-url \"https://example.com/a b\"")
        assertEquals(RunStatus.USAGE, failed, "a space is not a URL: $unhandled")
        val (other, otherStatus) = run("device open-url https://example.com")
        assertEquals(RunStatus.FAILED, otherStatus)
        assertTrue(other.endsWith("  FAIL device open-url: the app does not handle https://example.com"), other)
        // Parsed as strictly as the reference's Foundation: RFC 3986's characters only, `%` before two hex digits,
        // brackets only around an IP literal.
        for (url in listOf("gadget://x/é", "gadget://x/2?x=[1]", "gadget://x/%zz", "gadget://x/２", "gadget://[x/1")) {
            val (refused, refusedStatus) = run("device open-url $url")
            assertEquals(RunStatus.USAGE, refusedStatus, "$url: $refused")
        }
        for (url in listOf("gadget://[::1]:8080/a", "gadget://x/a%20b?q=1#f", "gadget:x")) {
            val (opened, openedStatus) = run("device open-url $url; expect link=$url")
            assertEquals(RunStatus.OK, openedStatus, "$url: $opened")
        }
        val (unknown, unknownStatus) = run("device push pong")
        assertEquals(RunStatus.FAILED, unknownStatus)
        assertTrue(unknown.endsWith("  FAIL unknown push 'pong'; pushes: ping"), unknown)
    }

    @Test
    fun screenCommandsFailInTheBackground() {
        val (text, status) = run("device background; expect phase=background; camera")
        assertEquals(RunStatus.FAILED, status)
        assertTrue(text.endsWith("  FAIL camera is unavailable: the app is in the background (device foreground first)"), text)
        val (back, ok) = run("device background; device foreground; expect phase=foreground; camera; expect prompt=camera")
        assertEquals(RunStatus.OK, ok, back)
    }

    @Test
    fun networkOfflineFailsEveryMockedCall() {
        val (text, status) = run(
            "mock gadget.fetch boom; device network offline; expect online=no; fetch; expect fetched=offline call=gadget.fetch; " +
                "device network online; expect online=yes; fetch; expect fetched=boom; fetch; expect fetched=ok",
        )
        assertEquals(RunStatus.OK, status, "the armed fault outlives the offline call:\n$text")
    }

    @Test
    fun location() {
        val (text, status) = run(
            "device location 52.2297,21.0122; expect location=52.2297,21.0122; device location -33,-70.5; " +
                "expect location=-33.0,-70.5; device location none; expect location=none",
        )
        assertEquals(RunStatus.OK, status, text)
    }

    @Test
    fun biometry() {
        val (text, status) = run(
            "unlock; expect prompt=biometry; device biometry match; expect faceID=success prompt=none; " +
                "unlock; device biometry fail; expect faceID=failure; " +
                "device biometry unenrolled; unlock; expect faceID=unavailable prompt=none; device biometry enrolled",
        )
        assertEquals(RunStatus.OK, status, text)
        val (none, failed) = run("device biometry match")
        assertEquals(RunStatus.FAILED, failed)
        assertTrue(none.endsWith("  FAIL device biometry: no biometric prompt is pending"), none)
        val (other, _) = run("camera; device biometry fail")
        assertTrue(other.endsWith("  FAIL device biometry: no biometric prompt is pending"), other)
    }

    @Test
    fun relaunchStartsTheStateOverAndKeepsTheDevice() {
        val (text, status) = run(
            "device permission camera granted; device network offline; device push ping; expect pings=1 online=no; " +
                "unlock; expect prompt=biometry; device relaunch; expect pings=0 prompt=none online=no phase=none; " +
                "camera; expect camera=granted",
        )
        assertEquals(RunStatus.OK, status, text)
    }

    /** CONTRACT.md §2.4: what the app's clients stored stays. An [AppFactory] builds them once per host. */
    @Test
    fun relaunchKeepsWhatAPreparedAppsClientsStored() = runBlocking {
        for ((host, after) in listOf(Notebook.preparedHost() to "hi", Notebook.rebuildingHost() to "none")) {
            val runner = host.makeRunner()
            runner.launch()
            val result = runner.run("write hi; device relaunch; expect note=$after")
            assertEquals(RunStatus.OK, result.status, StepFormatter.text(result.steps))
        }
    }

    @Test
    fun relaunchCancelsTheOldStoresTimers() = runBlocking {
        val runner = TinyAppConfig.headless(AgentDeviceConfig(relaunch = true)).makeRunner()
        runner.launch()
        val result = runner.run("open 2; save; expect pending=1; device relaunch; expect screen=items pending=0")
        assertEquals(RunStatus.OK, result.status, StepFormatter.text(result.steps))
        assertEquals(
            "> device relaunch\n  screen=items items=3 loading=false calls=items.fetch",
            StepFormatter.text(result.steps[3]),
        )
    }

    @Test
    fun theJsonFormCarriesAPromptOnlyWhenOneIsPending() {
        val steps = runBlocking {
            val runner = Gadget.host().makeRunner()
            runner.launch()
            runner.run("camera; device prompt allow").steps
        }
        val json = StepFormatter.json(steps)
        assertEquals(2, json.split("\"prompt\" : \"camera\"").size)
        assertEquals(2, json.split("\"prompt\"").size)
    }

    @Test
    fun docsListTheConfiguredSubcommands() {
        val docs = AgentRegistry.runtimeCommands(device = Gadget.device.surface).map { it.usage }
        assertEquals(
            listOf(
                "expect k=v [k=v …]", "advance <duration>", "mock <client.method> <error>",
                "device permission <service> <not-determined|granted|denied|limited>", "device prompt <allow|deny|limited>",
                "device open-url <url>", "device push <name> [tap]", "device background", "device foreground",
                "device relaunch", "device network <online|offline>", "device location <lat>,<lon>|none",
                "device biometry <enrolled|unenrolled|match|fail>",
            ),
            docs,
        )
        assertEquals(3, AgentRegistry.runtimeCommands().size)
        assertTrue(
            "error=<code|none>, pending=<n> or prompt=<service|none>" in
                AgentRegistry.runtimeCommands(device = Gadget.device.surface)[0].help,
        )
    }

    @Test
    fun theCoverageGuardNamesUnusedDeviceCommands() {
        val directory = Files.createTempDirectory("agentctl-device").toFile()
        try {
            File(directory, "a.appctl").writeText("device push ping tap\ndevice network offline\nexpect online=no\n")
            val coverage = AgentCoverage(Gadget.Screen.registry, directory) { Gadget.host().makeRunner() }
            assertEquals(
                listOf(
                    "device permission", "device prompt", "device open-url", "device background", "device foreground",
                    "device relaunch", "device location", "device biometry",
                ),
                coverage.unusedDeviceCommands(),
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
