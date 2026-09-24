package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.runtime.AppCtlConfig
import io.github.olbartek.agentctl.runtime.HelpExamples
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What this guards: the CLI's exit codes (CONTRACT.md §5), its help pages, and where it looks for files. */
class CliTest {
    private val subcommands = listOf(
        listOf("run"), listOf("state"), listOf("screens"), listOf("docs"), listOf("test"), listOf("snapshots"), listOf("check"),
        listOf("app"), listOf("app", "launch"), listOf("app", "run"), listOf("app", "state"), listOf("app", "screens"),
    )

    @Test
    fun helpIsExitZeroOnStdout() {
        val help = tinyctl("--help")
        assertEquals(0, help.status)
        assertTrue(help.out.startsWith("Usage: "), help.out)
        assertEquals("", help.err)
    }

    @Test
    fun malformedCommandLinesAreUsageErrors() {
        for (args in listOf(arrayOf("bogus"), arrayOf("run"), arrayOf("run", "a", "b"), arrayOf("run", "--nope", "x"), arrayOf("app", "run", "--port", "x"))) {
            val result = tinyctl(*args)
            assertEquals(2, result.status, "${args.toList()}: ${result.combined}")
            assertTrue(result.err.contains("Error: "), result.err)
        }
        // A command group without a subcommand prints its usage as an error.
        assertEquals(2, tinyctl().status)
    }

    /** Every page is rendered from the host's examples, so none names another app's CLI or screens. */
    @Test
    fun everyHelpPageNamesTheHostsInvocation() {
        for (args in subcommands) {
            val page = tinyctl(*(args + "--help").toTypedArray()).out
            val stripped = page.replace(".appctl", "").replace("appctl-seed", "")
            assertFalse("appctl" in stripped, "${args.joinToString(" ")} --help names appctl:\n$page")
            if (args.size == 1 && args[0] in setOf("run", "state", "docs", "test", "check", "snapshots")) {
                assertTrue("./tinyctl ${args[0]}" in page, "${args[0]} --help has no example:\n$page")
            }
        }
        assertTrue("./tinyctl run \"open 2; save\"" in tinyctl("run", "--help").out)
        assertTrue("Command reference: examples/tinyapp/agent-commands.md (or ./tinyctl screens)." in tinyctl("--help").out)
    }

    @Test
    fun theDefaultHelpNamesNoHostApp() {
        val defaults = HelpExamples(invocation = "xctl")
        val config = AppCtlConfig(
            name = "X",
            applicationId = "x",
            help = defaults,
            mockMethods = emptyList(),
            docsText = TinyAppConfig.docsText,
            screens = emptyList(),
            makeHeadless = { TinyAppConfig.headless() },
            makeLive = { latency, dispatcher -> TinyAppConfig.live(latency, dispatcher) },
        )
        val page = cli("run", "--help", config = config).out
        assertTrue("xctl run \"<command>; <command>\"" in page, page)
        assertFalse("open 2" in page)
        assertEquals("<command>", defaults.runScript(9))
    }

    @Test
    fun testExitCodes() {
        val missing = tinyctl("test", "/nonexistent.appctl")
        assertEquals(3, missing.status)
        assertEquals("FAIL nonexistent\n  cannot read /nonexistent.appctl\n0 passed, 1 failed\n", missing.out)
        val all = tinyctl("test")
        assertEquals(0, all.status, all.combined)
        assertTrue(all.out.endsWith("3 passed, 0 failed\n"), all.out)
    }

    @Test
    fun aFailingScenarioExitsOne() {
        val file = Files.createTempFile("broken", ".appctl").toFile().apply { writeText("open 2\nexpect saved=true\n") }
        val result = tinyctl("test", file.path)
        assertEquals(1, result.status)
        assertTrue(result.out.startsWith("FAIL ${file.nameWithoutExtension}:2\n"), result.out)
        assertTrue("  FAIL expected saved=true, got saved=false\n" in result.out, result.out)
    }

    @Test
    fun noScenariosIsNotAPass() {
        val empty = Files.createTempDirectory("empty").toFile()
        File(empty, "settings.gradle.kts").writeText("")
        val result = tinyctl("test").let { cli("test", config = TinyAppConfig.appCtl, workingDirectory = empty) }
        assertEquals(3, result.status)
        assertTrue(result.err.startsWith("error: no scenario files (*.appctl) in "), result.err)
    }

    @Test
    fun theRootIsFoundByItsMarkerOrAppctlRoot() {
        // From a subdirectory, the CLI walks up to settings.gradle.kts.
        val fromModule = cli("docs", "--check", config = TinyAppConfig.appCtl, workingDirectory = File(repositoryRoot, "examples/tinyapp/src"))
        assertEquals(0, fromModule.status, fromModule.combined)
        // APPCTL_ROOT wins over the working directory.
        val nowhere = Files.createTempDirectory("nowhere").toFile()
        val viaEnvironment = cli("docs", "--check", config = TinyAppConfig.appCtl, environment = mapOf("APPCTL_ROOT" to repositoryRoot.path), workingDirectory = nowhere)
        assertEquals(0, viaEnvironment.status)
        val lost = cli("docs", "--check", config = TinyAppConfig.appCtl, workingDirectory = nowhere)
        assertEquals(3, lost.status)
        assertTrue(lost.err.startsWith("error: cannot find the repo root (no settings.gradle.kts above "), lost.err)
    }

    @Test
    fun staleDocsAreReportedWithTheHostsInvocation() {
        val root = Files.createTempDirectory("stale").toFile()
        File(root, "settings.gradle.kts").writeText("")
        File(root, "examples/tinyapp").mkdirs()
        File(root, "examples/tinyapp/agent-commands.md").writeText("old")
        val result = cli("docs", "--check", config = TinyAppConfig.appCtl, workingDirectory = root)
        assertEquals(1, result.status)
        assertEquals("examples/tinyapp/agent-commands.md is stale. Run ./tinyctl docs.\n", result.out)
        assertEquals(0, cli("docs", config = TinyAppConfig.appCtl, workingDirectory = root).status)
        assertEquals(0, cli("docs", "--check", config = TinyAppConfig.appCtl, workingDirectory = root).status)
    }

    @Test
    fun sessionsAppendWhatSucceeded() {
        val directory = Files.createTempDirectory("session").toFile()
        val session = File(directory, "s.session").path
        assertEquals(0, tinyctl("run", "--session", session, "open 2; save").status)
        val second = tinyctl("run", "--session", session, "expect cooldown=3; frob")
        assertEquals(1, second.status)
        assertTrue(second.out.startsWith("> expect cooldown=3\n"), "a session run prints no (launch): ${second.out}")
        assertEquals("open 2\nsave\nexpect cooldown=3\n", File(session).readText())
        assertTrue("cooldown=3" in tinyctl("state", "--session", session).out)
    }

    @Test
    fun theBridgeCommandsReportAnUnreachableAppAsInternal() {
        val result = tinyctl("app", "state", "--port", "1")
        assertEquals(3, result.status)
        assertTrue(result.err.startsWith("error: cannot reach the app's agent bridge on 127.0.0.1:1 (is the app running? ./tinyctl app launch)"), result.err)
    }
}
