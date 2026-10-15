package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.AgentDeviceConfig
import io.github.olbartek.agentctl.StepFormatter
import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.examples.tinymvvm.AppCoordinator
import io.github.olbartek.agentctl.examples.tinymvvm.TinyMVVMConfig
import io.github.olbartek.agentctl.runtime.ScenarioRunner
import io.github.olbartek.agentctl.runtime.ScriptRunner
import io.github.olbartek.agentctl.testsupport.AgentCoverage
import io.github.olbartek.agentctl.testsupport.AgentScenarioChecks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What this guards: the engine is architecture-neutral. TinyApp (a store, `StoreDriver`) and TinyMVVM (Jetpack
 * ViewModels under a coordinator, `ViewModelDriver`) are the same app, and every script prints the same bytes and
 * exits the same way on both: TinyApp's scenarios, failures of every kind, `advance`, `mock`, `device` and a relaunch
 * (agentctl-ios's DriverParityTests, the same scripts).
 */
class DriverParityTest {
    private val scripts: List<String> = ScenarioRunner.files(scenariosDirectory).map { it.readText() } + listOf(
        "open 2; save; advance 1m",
        "open 2; save; back",
        "back",
        "mock items.fetch timeout; refresh; retry",
        "mock items.fetch bogus",
        "open x",
        "open 9; expect error=notFound; open 1",
        "open 1; device push item-updated; device push item-updated tap",
        "retry",
        "open 2; save; open 1",
        "open 2; save; save; expect error=cooldown; advance 1s; expect cooldown=2",
        "device open-url tinyapp://items/3; device open-url tinyapp://items/x",
        "device relaunch",
    )

    private fun <A> run(runner: ScriptRunner<A>, script: String): String = runBlocking {
        val launch = runner.launch()
        val result = runner.run(script)
        StepFormatter.text(listOf(launch.step) + result.steps) + "\nexit=${result.status.code}"
    }

    @Test
    fun tinyMVVMPrintsWhatTinyAppPrints() {
        assertTrue(scripts.size >= 18)
        for (script in scripts) {
            val store = run(TinyAppConfig.headless().makeRunner(), script)
            val mvvm = run(TinyMVVMConfig.headless().makeRunner(), script)
            assertEquals(store, mvvm, "$script\n--- store\n$store\n--- MVVM\n$mvvm")
        }
    }

    /** A relaunch throws the app away mid-countdown and after a failure, and both drivers start over alike. */
    @Test
    fun aRelaunchIsTheSameOnBothDrivers() {
        val script = "open 2; save; expect pending=1; device relaunch; expect screen=items pending=0; " +
            "mock items.fetch network; device relaunch; expect error=network; device relaunch; open 3"
        val store = run(TinyAppConfig.headless(AgentDeviceConfig(relaunch = true)).makeRunner(), script)
        val mvvm = run(TinyMVVMConfig.headless(AgentDeviceConfig(relaunch = true)).makeRunner(), script)
        assertTrue(store.endsWith("exit=0"), store)
        assertEquals(store, mvvm, "--- store\n$store\n--- MVVM\n$mvvm")
    }

    /** The coverage guards and scenario checks are architecture-neutral too: aimed at TinyMVVM, they hold. */
    @Test
    fun theCoverageGuardsAndScenarioChecksHoldForTheMVVMApp() {
        val coverage = AgentCoverage(AppCoordinator.registry, scenariosDirectory) { TinyMVVMConfig.headless().makeRunner() }
        assertEquals(emptyList(), coverage.unusedCommands())
        assertEquals(emptyList(), coverage.unusedDeviceCommands())
        assertEquals(emptyList(), coverage.undocumentedSummaryKeys())
        assertEquals(emptyList(), coverage.unvisitedScreens())
        val checks = AgentScenarioChecks(TinyMVVMConfig.appCtl, repositoryRoot)
        val problems = checks.allPass() + checks.endWithExpect() + checks.docsCurrent() + checks.deterministic(runs = 5)
        assertEquals(emptyList(), problems, problems.joinToString("\n"))
    }
}
