package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.Store
import io.github.olbartek.agentctl.examples.tinyapp.ItemsClient
import io.github.olbartek.agentctl.examples.tinyapp.TinyApp
import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.examples.tinyapp.TinyRoot
import io.github.olbartek.agentctl.examples.tinyapp.TinyRootAgent
import io.github.olbartek.agentctl.runtime.AppCtlConfig
import io.github.olbartek.agentctl.runtime.HeadlessHost
import io.github.olbartek.agentctl.runtime.HelpExamples
import io.github.olbartek.agentctl.testsupport.AgentScenarioChecks
import io.github.olbartek.agentctl.testsupport.NoScenariosFound
import io.github.olbartek.agentctl.testsupport.RepoRootNotFound
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What this guards: each of [AgentScenarioChecks]' checks passes on a repository that keeps its promise and names
 * the problem in one that breaks it. Each case is a temporary repository of TinyApp scenarios; a drifting config
 * (every other app it builds starts differently) stands in for an app whose runs differ.
 */
class AgentScenarioChecksTest {
    private fun repo(vararg scenarios: Pair<String, String>): File {
        val root = Files.createTempDirectory("checks").toFile()
        File(root, "settings.gradle.kts").writeText("")
        File(root, "scenarios").mkdirs()
        for ((name, text) in scenarios) File(root, "scenarios/$name").writeText(text)
        return root
    }

    /** How every other app the config builds (the 2nd, the 4th…) starts differently. */
    private enum class Drift {
        /** Alike. */
        NONE,

        /** Its launch's fetch fails, so its steps show `error=network`. */
        FAILING_FETCH,

        /** Its state differs where no step shows it (the next stack id). */
        HIDDEN_STATE,
    }

    private fun config(drift: Drift = Drift.NONE, rootMarker: String = "settings.gradle.kts"): AppCtlConfig<*, *> {
        var built = 0
        return AppCtlConfig(
            name = "Fixture",
            rootMarker = rootMarker,
            applicationId = "io.github.olbartek.agentctl.fixture",
            scenariosPath = "scenarios",
            docsPath = "docs/agent-commands.md",
            help = HelpExamples(invocation = "./fixturectl"),
            mockMethods = TinyAppConfig.mockMethods,
            docsText = TinyAppConfig.docsText,
            screens = TinyAppConfig.screens,
            makeHeadless = {
                built += 1
                val drifts = if (built % 2 == 0) drift else Drift.NONE
                HeadlessHost(TinyRootAgent, TinyAppConfig.mockMethods) { environment ->
                    when (drifts) {
                        Drift.NONE -> TinyApp.store(environment)
                        Drift.FAILING_FETCH -> {
                            environment.mocks.faults.set("items.fetch", "network")
                            TinyApp.store(environment)
                        }
                        Drift.HIDDEN_STATE -> Store(
                            initialState = TinyRoot.State(nextId = 100),
                            reducer = TinyRoot.reducer(ItemsClient.mock(environment.mocks), environment.clock),
                            scope = environment.scope,
                        )
                    }
                }
            },
            makeLive = { latency, dispatcher -> TinyAppConfig.live(latency, dispatcher) },
        )
    }

    private fun checks(root: File, drift: Drift = Drift.NONE) = AgentScenarioChecks(config(drift), start = root)

    private val browse = "expect screen=items items=3\nopen 2\nsave\nexpect saved=true\n"

    @Test
    fun theRootIsFoundByTheMarkerFromBelow() {
        val root = repo("a.appctl" to browse)
        val below = File(root, "deep/er").apply { mkdirs() }
        assertEquals(root.canonicalFile, AgentScenarioChecks(config(), start = below).root.canonicalFile)
    }

    @Test
    fun noMarkerAndNoScenariosAreErrors() {
        // A marker no directory has, wherever the temporary directory is.
        val marker = "no-such-marker-${System.nanoTime()}"
        assertFailsWith<RepoRootNotFound> { AgentScenarioChecks(config(rootMarker = marker), start = repo()) }
        assertFailsWith<NoScenariosFound> { checks(repo()) }
    }

    @Test
    fun allPass() {
        assertEquals(emptyList(), checks(repo("a.appctl" to browse)).allPass())
        val problems = checks(repo("a.appctl" to browse, "b.appctl" to "open 2\nexpect saved=true\n")).allPass()
        assertEquals(1, problems.size, problems.joinToString("\n"))
        assertTrue(problems[0].startsWith("FAIL b:2"), problems[0])
    }

    @Test
    fun endWithExpect() {
        assertEquals(emptyList(), checks(repo("a.appctl" to browse)).endWithExpect())
        val problems = checks(repo("a.appctl" to "expect screen=items\nopen 2\n", "b.appctl" to "# nothing\n")).endWithExpect()
        assertEquals(listOf("a.appctl ends with `open 2` (line 2), not an expect", "b.appctl has no commands"), problems)
    }

    @Test
    fun deterministic() {
        assertEquals(emptyList(), checks(repo("a.appctl" to browse)).deterministic(runs = 3))
        val problems = checks(repo("a.appctl" to browse), Drift.FAILING_FETCH).deterministic(runs = 3)
        assertEquals(1, problems.size, problems.joinToString("\n"))
        assertTrue(problems[0].startsWith("a.appctl produced 2 different outputs in 3 runs; first difference at line "), problems[0])
        assertFailsWith<IllegalArgumentException> { checks(repo("a.appctl" to browse)).deterministic(runs = 1) }
    }

    @Test
    fun sessionReplayMatches() {
        val one = "expect screen=items\n"
        assertEquals(emptyList(), checks(repo("a.appctl" to browse, "one.appctl" to one)).sessionReplayMatches())
        // More parts than lines: a part per line.
        assertEquals(emptyList(), checks(repo("a.appctl" to browse)).sessionReplayMatches(parts = 10))
        // The single run is the first app built; the first part's app starts with a failing fetch.
        val problems = checks(repo("a.appctl" to browse), Drift.FAILING_FETCH).sessionReplayMatches()
        assertEquals(1, problems.size, problems.joinToString("\n"))
        assertTrue(problems[0].startsWith("a.appctl, part 1 of 3 failed after the session replay"), problems[0])
        // Passing, but showing the failed fetch: the steps differ.
        val listing = "expect screen=items\nrefresh\nexpect screen=items\n"
        val steps = checks(repo("a.appctl" to listing), Drift.FAILING_FETCH).sessionReplayMatches().single()
        assertTrue(steps.startsWith("a.appctl: the steps of a run split into 3 parts differ from one run; first difference at line 2:"), steps)
        // The same steps, but the last part's app ends in another state.
        val state = checks(repo("a.appctl" to browse), Drift.HIDDEN_STATE).sessionReplayMatches().single()
        assertTrue(state.startsWith("a.appctl: the state after a run split into 3 parts differs from one run's; first difference at line "), state)
        val failing = checks(repo("a.appctl" to "open 2\nexpect saved=true\n")).sessionReplayMatches()
        assertEquals(listOf("a.appctl fails in one run, so its session replay was not compared"), failing)
    }

    @Test
    fun docsCurrent() {
        val root = repo("a.appctl" to browse)
        val checks = checks(root)
        assertEquals(
            listOf("docs/agent-commands.md does not exist at ${File(root, "docs/agent-commands.md").path}. Run ./fixturectl docs."),
            checks.docsCurrent(),
        )
        File(root, "docs").mkdirs()
        File(root, "docs/agent-commands.md").writeText(checks.config.docsMarkdown)
        assertEquals(emptyList(), checks.docsCurrent())
        File(root, "docs/agent-commands.md").writeText("# Old\n")
        val stale = checks.docsCurrent().single()
        assertTrue(stale.startsWith("docs/agent-commands.md is stale. Run ./fixturectl docs. first difference at line 1:\n  committed: # Old\n  generated: "), stale)
    }

    @Test
    fun readmeListsAll() {
        val root = repo("a.appctl" to browse, "b.appctl" to browse)
        val readme = File(root, "scenarios/README.md")
        assertEquals(listOf("cannot read ${readme.path}: the scenarios README must list every scenario file"), checks(root).readmeListsAll())
        readme.writeText("| [`scenarios/a.appctl`](a.appctl) | one |\n| `b.appctl` | two |\nAll of them: `*.appctl`.\n")
        assertEquals(emptyList(), checks(root).readmeListsAll())
        readme.writeText("`a.appctl`, `ghost.appctl`, and b.appctl in passing\n")
        assertEquals(
            listOf("README.md does not list: b.appctl", "README.md names files that are not in scenarios: ghost.appctl"),
            checks(root).readmeListsAll(),
        )
    }

    @Test
    fun noStepShowsFindsLeaksIgnoringCaseAndNotInTheEcho() {
        val root = repo("a.appctl" to browse)
        assertEquals(emptyList(), checks(root).noStepShows(listOf("hunter2", "Third item")))
        // `open 2` is only in the echo of the command the agent sent.
        assertEquals(emptyList(), checks(root).noStepShows(listOf("open 2")))
        val problems = checks(root).noStepShows(listOf("SECOND ITEM"), personalCommands = setOf("open"))
        assertTrue(problems.isNotEmpty(), "no leaks found")
        assertTrue(problems.all { it.startsWith("a.appctl: `") }, problems.joinToString("\n"))
        assertTrue(problems.any { it.startsWith("a.appctl: `SECOND ITEM` in `open 2`:   screen=items/2") }, problems.joinToString("\n"))
        assertTrue(problems.any { it.startsWith("a.appctl: `2` in `open 2`: ") }, problems.joinToString("\n"))
    }

    @Test
    fun noStepShowsReportsWhatItCannotScanFor() {
        val root = repo("a.appctl" to browse)
        assertEquals(
            listOf(
                "no scenario types `password`, so none of its values is scanned for",
                "nothing to scan for: no secrets were given and no scenario types a personal command",
            ),
            checks(root).noStepShows(emptyList(), personalCommands = setOf("password")),
        )
    }

    @Test
    fun noStepShowsReportsAFailedRun() {
        val problems = checks(repo("a.appctl" to "open 2\nexpect saved=true\n")).noStepShows(listOf("hunter2"))
        assertEquals(1, problems.size, problems.joinToString("\n"))
        assertTrue(problems[0].startsWith("a.appctl failed, so the steps after its failure were not scanned:\nFAIL a:2"), problems[0])
    }

    @Test
    fun noStepShowsCapsTheLeaksItLists() {
        // The launch, 30 refreshes and the expect: 32 steps, each showing `items=`.
        val script = (1..30).joinToString("") { "refresh\n" } + "expect items=3\n"
        val problems = checks(repo("a.appctl" to script)).noStepShows(listOf("items="))
        assertEquals(21, problems.size, problems.joinToString("\n"))
        assertEquals("… and 12 more leaks", problems.last())
    }
}
