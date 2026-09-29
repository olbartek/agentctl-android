package io.github.olbartek.agentctl.examples.agentshop.app

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.olbartek.agentctl.examples.agentshop.app.design.UiTestValue
import io.github.olbartek.agentctl.examples.agentshop.app.design.UiTesting
import io.github.olbartek.agentctl.examples.agentshop.models.ScheduledFaults
import org.junit.After
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * The driver every generated UI test runs on (`generated/<Group>UiTests.kt`, written by `bench/gen_uitests.py`), the port of
 * the reference's `ShopUITestCase.swift`.
 *
 * A generated test replays one scenario file through the real UI: each script command becomes a tap, some typing or a
 * switch, and each `expect` becomes a wait for what the screen shows. Elements are found by the test tags in
 * `design/UiTestTags.kt`. Every call takes the scenario line it came from, and a failure names it
 * (`auth-login-happy-path.appctl:7  submit`).
 *
 * The app is launched the way `app test` launches it for the bridge: no saved session, no mock latency. A scenario's
 * `mock` lines become `mock-fault` extras (`<method>#<n>=<code>`), since a UI test cannot send them later. The debug
 * app's bridge is present but idle: it listens on an ephemeral port (`agent-port 0`), so it never clashes with another
 * copy's. `ui-testing` gives every launch a fresh store (the tests share a process) and turns off autofill hints.
 *
 * Each test's duration is logged under the tag `ShopUiTiming` (`<class>#<test> <ms> ms <passed|failed>`), which the
 * benchmark reads from logcat.
 */
@RunWith(AndroidJUnit4::class)
abstract class ShopUiTestCase {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val timing = object : TestWatcher() {
        private var start = 0L

        override fun starting(description: Description) {
            start = SystemClock.elapsedRealtime()
        }

        override fun succeeded(description: Description) = report(description, "passed")

        override fun failed(e: Throwable, description: Description) = report(description, "failed")

        private fun report(description: Description, outcome: String) {
            val ms = SystemClock.elapsedRealtime() - start
            Log.i(TIMING_TAG, "${description.className}#${description.methodName} $ms ms $outcome")
        }
    }

    private var scenario: ActivityScenario<MainActivity>? = null

    /** How long to wait for a screen, an element or a value. */
    private val timeoutMillis = 10_000L

    fun launch(faults: List<String> = emptyList()) {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(UiTesting.EXTRA, true)
            .putExtra("clear-session", true)
            .putExtra("mock-latency", 0)
            .putExtra("agent-port", 0)
            .putExtra(ScheduledFaults.ARGUMENT, faults.toTypedArray())
        scenario = ActivityScenario.launch(intent)
    }

    @After
    fun closeApp() {
        scenario?.close()
    }

    // Commands

    fun tap(tag: String, line: String) {
        onScreen(tag, line).performClick()
    }

    fun type(tag: String, text: String, line: String) {
        // Sets the field's text as typing would, without the keyboard: nothing to dismiss, nothing covered.
        onScreen(tag, line).performTextReplacement(text)
        // The field hands its text to the store, which comes back in the next frame.
        compose.waitForIdle()
    }

    fun setSwitch(tag: String, on: Boolean, line: String) {
        val expected = if (on) "on" else "off"
        val element = onScreen(tag, line)
        if (value(node(tag)) != expected) element.performClick()
        require(line, "$tag did not turn $expected") { value(node(tag)) == expected }
    }

    /** `back`: the screen's own back button, or the top bar's; both are `nav.back`. */
    fun back(line: String) {
        onScreen("nav.back", line).performClick()
    }

    // Expectations

    fun expectScreen(path: String, line: String) {
        require(line, "screen $path did not appear") { exists("screen:$path") }
    }

    /** A value a screen only shows when it is set (the applied promo code) counts as `none` when it is not shown. */
    fun expectValue(tag: String, value: String, line: String) {
        if (value == "none" && !poll(1_000) { exists(tag) }) return
        require(line, "no element $tag") { exists(tag) }
        require(line, "$tag is '${describe(tag)}', expected '$value'") {
            val current = value(node(tag))
            // A switch or a checkbox shows on/off where the summary says true/false.
            current == value || mapOf("on" to "true", "off" to "false")[current] == value
        }
    }

    /** A button a screen hides when it can't be used (an order that can't be cancelled) counts as disabled. */
    fun expectEnabled(tag: String, enabled: Boolean, line: String) {
        require(line, "$tag is not ${if (enabled) "enabled" else "disabled"}") {
            val node = find(tag)
            if (enabled) node != null && node.isEnabled() else node == null || !node.isEnabled()
        }
    }

    fun expectError(code: String, line: String) {
        require(line, "error $code is not shown") { exists("error:$code") }
    }

    fun expectNoError(line: String) {
        require(line, "an error is shown: ${errors().firstOrNull()}") { errors().isEmpty() }
    }

    // Helpers

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun find(tag: String): SemanticsNode? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull()

    private fun exists(tag: String): Boolean = find(tag) != null

    private fun SemanticsNode.isEnabled(): Boolean = !config.contains(SemanticsProperties.Disabled)

    private fun value(interaction: SemanticsNodeInteraction): String? =
        runCatching { interaction.fetchSemanticsNode().config.getOrNull(UiTestValue) }.getOrNull()

    private fun describe(tag: String): String = value(node(tag)) ?: "<no value>"

    private fun errors(): List<String> = compose
        .onAllNodes(
            SemanticsMatcher("has an error: tag") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("error:") == true },
            useUnmergedTree = true,
        )
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    /**
     * Waits for the element, then gets it on screen: scrolls its closest scrollable parent (a form under the
     * keyboard, a feed row, a chip in a horizontal row) until it is fully visible. Costs nothing when it already is.
     */
    private fun onScreen(tag: String, line: String): SemanticsNodeInteraction {
        require(line, "no element $tag") { exists(tag) }
        val element = node(tag)
        val hasScrollParent = generateSequence(element.fetchSemanticsNode().parent) { it.parent }
            .any { it.config.contains(SemanticsActions.ScrollBy) }
        if (hasScrollParent) element.performScrollTo()
        return element
    }

    private fun poll(timeout: Long = timeoutMillis, condition: () -> Boolean): Boolean = try {
        compose.waitUntil(timeout) { condition() }
        true
    } catch (_: ComposeTimeoutException) {
        false
    }

    /** Waits for `condition`, and fails with the scenario line if it does not come true in time. */
    private fun require(line: String, message: String, condition: () -> Boolean) {
        if (poll(condition = condition)) return
        throw AssertionError("$line: $message")
    }

    companion object {
        const val TIMING_TAG: String = "ShopUiTiming"
    }
}
