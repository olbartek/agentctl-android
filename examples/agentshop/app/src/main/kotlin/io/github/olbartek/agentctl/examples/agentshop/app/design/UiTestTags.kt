package io.github.olbartek.agentctl.examples.agentshop.app.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

// The contract between AgentShop's views and its generated UI tests (`bench/gen_uitests.py`), the reference's
// `DesignSystem/UITestIdentifiers.swift` with test tags for accessibility identifiers. A UI test drives exactly what a
// scenario script drives, so every element a script command stands for carries a tag derived from that command:
//
// | Element                                  | Test tag                            |
// |------------------------------------------|-------------------------------------|
// | The screen                               | `screen:<path>` ([screenTag])       |
// | A command's button, field or switch      | `<Screen>.<command>`                |
// | A command's choice, e.g. `tab cart`      | `<Screen>.<command>.<argument>`     |
// | A summary value shown on screen          | `<Screen>.<key>`, with the value as [UiTestValue] ([summaryValue]) |
// | An error                                 | `error:<code>` ([InlineError], [ErrorView]) |
// | A container's `back`                     | `nav.back` ([BackButton], [NavBar]) |
//
// `<Screen>` is the screen's agent name, as `./appctl screens` prints it in brackets. The root sets
// `testTagsAsResourceId`, so the tags are also resource-ids to uiautomator and screenshot tools.

/**
 * The value a UI test reads off an element: a summary value exactly as the step output prints it, a field's text, or
 * `on`/`off` for a switch or a choice. The counterpart of the reference's `accessibilityValue`.
 */
val UiTestValue: SemanticsPropertyKey<String> = SemanticsPropertyKey("UiTestValue")

/** See [UiTestValue]. */
var SemanticsPropertyReceiver.uiTestValue: String by UiTestValue

/**
 * Marks a screen for UI tests as `screen:<path>`, the same path the agent layer reports. Pass the screen's own
 * `<Screen>Agent.screenPath(state)`, so the two can never disagree.
 */
fun Modifier.screenTag(path: String): Modifier = testTag("screen:$path")

/** A summary value shown on screen: tag `<Screen>.<key>`, value exactly as the step output prints it. */
fun Modifier.summaryValue(tag: String, value: String): Modifier = testTag(tag).semantics { uiTestValue = value }

/** A switch's or a choice's state, as the reference reports it: `on` or `off` (and selected). */
fun Modifier.onOffValue(isOn: Boolean): Modifier = semantics {
    uiTestValue = if (isOn) "on" else "off"
    selected = isOn
}

/** Applies a test tag only when one is given. */
fun Modifier.testTagIfPresent(tag: String?): Modifier = if (tag != null) testTag(tag) else this
