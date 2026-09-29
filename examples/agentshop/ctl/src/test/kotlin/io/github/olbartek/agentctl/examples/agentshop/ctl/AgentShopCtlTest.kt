package io.github.olbartek.agentctl.examples.agentshop.ctl

import io.github.olbartek.agentctl.AgentRegistry
import io.github.olbartek.agentctl.DocsRenderer
import io.github.olbartek.agentctl.MockLatency
import io.github.olbartek.agentctl.StepFormatter
import io.github.olbartek.agentctl.examples.agentshop.app.AppFeatureAgent
import io.github.olbartek.agentctl.examples.agentshop.clients.MockAccounts
import io.github.olbartek.agentctl.examples.agentshop.clients.SessionStorage
import io.github.olbartek.agentctl.examples.agentshop.clients.StoredSession
import io.github.olbartek.agentctl.runtime.BridgeRequest
import io.github.olbartek.agentctl.runtime.BridgeRouter
import io.github.olbartek.agentctl.runtime.RunStatus
import io.github.olbartek.agentctl.runtime.ScenarioRunner
import io.github.olbartek.agentctl.testsupport.AgentCoverage
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * What this guards: the unit tests cover what `./appctl test` and `./appctl docs --check` cover, so the host's tests
 * alone catch a failing scenario or a stale command reference.
 */
class ScenarioTest {
    @Test
    fun thereAreScenarios() {
        assertEquals(105, scenarioFiles.size, "no *.appctl files at ${scenariosDirectory.path}")
    }

    /** Every scenario passes against AgentShop's own headless wiring. */
    @Test
    fun everyScenarioPasses() = runBlocking {
        for (file in scenarioFiles) {
            val result = ScenarioRunner.run(file) { headlessRunner() }
            assertTrue(result.passed, result.report)
        }
    }

    /** The same through the real CLI, as CI's `examples/agentshop/appctl test` runs it. */
    @Test
    fun theCliRunsThemAll() {
        val result = shopctl("test")
        assertEquals(0, result.status, result.combined)
        assertTrue(result.out.endsWith("105 passed, 0 failed\n"), result.out)
    }

    /** The committed command reference is what `./appctl docs` would write today. */
    @Test
    fun docsAreUpToDate() {
        val path = AgentShopConfig.appCtl.docsPath
        val existing = File(exampleRoot, path).readText()
        val rendered = DocsRenderer.render(
            screens = AgentShopConfig.screens,
            runtimeCommands = AgentRegistry.runtimeCommands(AgentShopConfig.docsText.mockExample),
            mockMethods = AgentShopConfig.mockMethods,
            text = AgentShopConfig.docsText,
        )
        assertEquals(rendered, existing, "$path is stale. Run examples/agentshop/appctl docs.")
        assertEquals(0, shopctl("docs", "--check").status)
    }
}

/**
 * The package's coverage guards, aimed at AgentShop's own registry and scenarios: every command used by a scenario,
 * every emitted summary key documented, every screen visited.
 */
class CoverageTest {
    private val coverage = AgentCoverage(AppFeatureAgent.registry, scenariosDirectory) { headlessRunner() }

    @Test
    fun everyCommandIsUsedByAScenario() {
        assertEquals(emptyList(), coverage.unusedCommands(), "commands not used by any scenario")
    }

    @Test
    fun everyEmittedSummaryKeyIsDocumented() {
        assertEquals(emptyList(), coverage.undocumentedSummaryKeys(), "undocumented summary keys")
    }

    /** No scenario stays on `launching`: it is left before the launch step is printed. */
    @Test
    fun everyScreenIsVisited() {
        val unvisited = coverage.unvisitedScreens()
        assertTrue(unvisited == listOf("launching") || unvisited.isEmpty(), "screens no scenario visits: $unvisited")
    }
}

/**
 * "The same script always prints the same output": determinism is a property of AgentShop's whole wiring — the
 * virtual clock its auth server measures codes on, incrementing UUIDs, the fixed date, zero mock latency, fresh
 * in-memory backends — so it is checked against AgentShop's config, not only the library's example.
 */
class DeterminismTest {
    /** Ten fresh runs of every scenario print identical steps. */
    @Test
    fun scenariosAreDeterministic() = runBlocking {
        for (file in scenarioFiles) {
            val outputs = (1..10).map { StepFormatter.text(ScenarioRunner.run(file) { headlessRunner() }.steps) }.toSet()
            assertEquals(1, outputs.size, "${file.name} produced ${outputs.size} different outputs")
        }
    }

    /**
     * What `--session` promises: resuming replays the saved commands into a fresh runner, so a script run in three
     * `run` calls against one runner must land in exactly the state one uninterrupted run reaches.
     */
    @Test
    fun sessionReplayMatchesASingleRun() = runBlocking {
        val parts = listOf("email alice@example.com; password Passw0rd!", "submit; expect screen=home/shop products=12", "advance 1s")
        val fresh = headlessRunner().also { assertEquals(RunStatus.OK, it.launch().status) }

        val split = headlessRunner().also { assertEquals(RunStatus.OK, it.launch().status) }
        for (part in parts) {
            val result = split.run(part)
            assertEquals(RunStatus.OK, result.status, "$part:\n${StepFormatter.text(result.steps)}")
        }

        val single = headlessRunner().also { assertEquals(RunStatus.OK, it.launch().status) }
        val whole = single.run(parts.joinToString("; "))
        assertEquals(RunStatus.OK, whole.status, StepFormatter.text(whole.steps))
        assertEquals(5, whole.steps.size)

        assertEquals(single.stateDump, split.stateDump)
        assertNotEquals(fresh.stateDump, split.stateDump, "the script left the state where launch put it")
    }
}

/**
 * AgentShop's **live** wiring — [AgentShopConfig.live], what the debug app runs behind its agent bridge — answers a
 * script with the same steps as the headless wiring `./appctl run` uses. Driven through the bridge's router on a
 * thread of its own (the main thread's stand-in): no server, no device. The script starts nothing that waits on the
 * clock, and the live host gets zero latency and in-memory session storage.
 */
class BridgeTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    @AfterTest
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun runReturnsTheSameStepsAsAppctl() = runBlocking {
        val script = "login-as alice; tab orders; open 1003; cancel; back"
        val runner = headlessRunner()
        assertEquals(RunStatus.OK, runner.launch().status)
        val result = runner.run(script)
        assertEquals(RunStatus.OK, result.status, StepFormatter.text(result.steps))
        val headless = StepFormatter.text(result.steps) + "\n"

        val response = withContext(dispatcher) {
            val live = AgentShopConfig.live(MockLatency.ZERO, dispatcher, SessionStorage.inMemory())
            // No views exist in a test, so this runner sends each screen's appearance itself, as a launch seed's does.
            val liveRunner = live.makeRunner(synthesizesAppearance = true)
            val launch = liveRunner.launch()
            assertEquals(RunStatus.OK, launch.status, "the live app did not settle at launch:\n${StepFormatter.text(launch.step)}")
            BridgeRouter(liveRunner) { "" }.handle(BridgeRequest("POST", "/run", body = script))
        }

        assertEquals(200, response.status)
        assertEquals(0, response.exitCode)
        assertEquals(headless, response.body)
        // Not vacuous: the script really went somewhere and did something.
        assertTrue("> cancel\n  screen=home/orders/1003" in headless, headless)
    }

    /** `clear-session` empties the storage the app handed its config. */
    @Test
    fun clearSessionForgetsTheAppsSession() {
        val storage = SessionStorage.inMemory(StoredSession(MockAccounts.session(MockAccounts.alice.user), remember = true))
        AgentShopConfig.appCtl(storage).clearSession()
        assertNull(storage.load())
    }
}
