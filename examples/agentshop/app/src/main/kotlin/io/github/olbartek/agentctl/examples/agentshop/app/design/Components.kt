package io.github.olbartek.agentctl.examples.agentshop.app.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.olbartek.agentctl.examples.agentshop.app.R

// The reference's DesignSystem views (Buttons, Controls, Layout, States), in Compose.

/**
 * The main call to action: a 50 dp capsule in the brand color. Shows a spinner while loading, and is disabled when
 * not enabled or while loading. Disabled, only the capsule fades, so the title stays readable.
 */
@Composable
fun PrimaryButton(
    title: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isEnabled: Boolean = true,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .testTagIfPresent(testTag)
            .fillMaxWidth()
            .height(Metrics.buttonHeight)
            .clip(CircleShape)
            .background(Palette.brand.copy(alpha = if (isEnabled || isLoading) 1f else 0.4f))
            .clickable(enabled = isEnabled && !isLoading, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = Typography.button, modifier = Modifier.alpha(if (isLoading) 0f else 1f))
        if (isLoading) CircularProgressIndicator(color = Palette.onBrand, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
    }
}

/** "Sign in with Google" / "Sign up with Google": the tinted social button with the Google logo. */
@Composable
fun GoogleButton(title: String, modifier: Modifier = Modifier, isLoading: Boolean = false, testTag: String? = null, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .testTagIfPresent(testTag)
            .fillMaxWidth()
            .height(Metrics.socialButtonHeight)
            .clip(RoundedCornerShape(Metrics.socialButtonRadius))
            .background(Palette.surfaceTint)
            .clickable(enabled = !isLoading, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLoading) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.9.dp))
        } else {
            Image(painterResource(R.drawable.ic_google), contentDescription = null, modifier = Modifier.size(18.9.dp))
        }
        Text(title, style = Typography.bodyMedium.copy(color = Palette.textBlack))
    }
}

/** A brand-colored text link, such as "Forgot Password" or "Resend". Grey when disabled. */
@Composable
fun LinkButton(
    title: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Typography.bodyMedium,
    underlined: Boolean = false,
    isEnabled: Boolean = true,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    Text(
        title,
        style = style.copy(
            color = if (isEnabled) Palette.brand else Palette.textSecondary,
            textDecoration = if (underlined && isEnabled) TextDecoration.Underline else null,
        ),
        modifier = modifier
            .testTagIfPresent(testTag)
            .clickable(enabled = isEnabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

/** A prompt followed by a link on one line: "Don't have an Account? Sign up here". */
@Composable
fun PromptLink(
    prompt: String,
    link: String,
    modifier: Modifier = Modifier,
    promptColor: Color = Palette.textBlack,
    linkStyle: TextStyle = Typography.link,
    underlined: Boolean = false,
    isEnabled: Boolean = true,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(prompt, style = Typography.body.copy(color = promptColor))
        LinkButton(link, style = linkStyle, underlined = underlined, isEnabled = isEnabled, testTag = testTag, onClick = onClick)
    }
}

/** The round back button in the top-left corner of the auth screens: `nav.back` for UI tests. */
@Composable
fun BackButton(modifier: Modifier = Modifier, testTag: String = "nav.back", onClick: () -> Unit) {
    Box(
        modifier = modifier
            .testTag(testTag)
            .size(40.dp)
            .clip(CircleShape)
            .background(Palette.surfaceTint)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_arrow_left), contentDescription = null, modifier = Modifier.size(32.dp))
    }
}

/** A checkbox with a label. Unchecked: a brand-outlined square; checked: a teal square with a white checkmark. */
@Composable
fun Checkbox(
    isOn: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    testTag: String? = null,
    label: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .testTagIfPresent(testTag)
            .fillMaxWidth()
            .toggleable(value = isOn, role = Role.Checkbox, onValueChange = onChange)
            .onOffValue(isOn),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .then(
                    if (isOn) Modifier.background(Palette.checkboxOn) else Modifier.border(1.dp, Palette.brand, RoundedCornerShape(4.dp)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isOn) Image(painterResource(R.drawable.ic_checkmark), contentDescription = null, modifier = Modifier.size(16.dp))
        }
        label()
    }
}

/** A horizontal rule with a centered caption: "or sign in with". */
@Composable
fun DividerLabel(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
        Text(
            text,
            style = Typography.bodyMedium.copy(color = Palette.textSubtle),
            maxLines = 1,
            modifier = Modifier.background(Palette.background).padding(8.dp),
        )
    }
}

/** The top of an auth screen: an optional back button, then the title. */
sealed interface HeaderStyle {
    /** A bold title centered below the back button's row ("Login", "Signup"). */
    data object Centered : HeaderStyle

    /** A medium title with a subtitle, aligned to the leading edge ("Forgot Password"). */
    data class Leading(val subtitle: String?) : HeaderStyle
}

@Composable
fun ScreenHeader(title: String, style: HeaderStyle = HeaderStyle.Centered, onBack: (() -> Unit)? = null) {
    when (style) {
        HeaderStyle.Centered -> Box(Modifier.fillMaxWidth()) {
            Text(
                title,
                style = Typography.screenTitle,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 34.dp).semantics { heading() },
            )
            if (onBack != null) BackButton(onClick = onBack)
        }
        is HeaderStyle.Leading -> Column(Modifier.fillMaxWidth()) {
            if (onBack != null) BackButton(onClick = onBack) else Spacer(Modifier.height(40.dp))
            Text(title, style = Typography.pageTitle, modifier = Modifier.padding(top = 33.dp).semantics { heading() })
            if (style.subtitle != null) {
                Text(style.subtitle, style = Typography.body.copy(color = Palette.textSecondary), modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

/**
 * The auth and onboarding screens' page: a white scroll view with the design's 28 dp side margins, clear of the
 * system bars and the keyboard, and no top bar (screens draw their own header).
 */
@Composable
fun FormScreen(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(start = Metrics.screenPadding, end = Metrics.screenPadding, top = 4.dp, bottom = 32.dp),
        content = content,
    )
}

/**
 * The top of a signed-in screen: a large title for a tab's first screen, or a back button (`nav.back`) and an inline
 * title for a pushed one, with trailing actions — the reference's navigation bar.
 */
@Composable
fun NavBar(title: String, large: Boolean = true, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(Palette.background)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("nav.back")) {
                    Image(painterResource(R.drawable.ic_arrow_left), contentDescription = "Back", modifier = Modifier.size(32.dp))
                }
            }
            if (!large) {
                Text(
                    title,
                    style = Typography.navTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            actions()
        }
        if (large) {
            Text(title, style = Typography.largeTitle, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).semantics { heading() })
        }
    }
}

/** An icon-only toolbar or row button, tagged for UI tests. */
@Composable
fun IconAction(icon: ImageVector, label: String, testTag: String, tint: Color = Palette.brand, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(testTag)) {
        Icon(icon, contentDescription = label, tint = tint)
    }
}

@Composable
fun LoadingView(title: String = "Loading…", modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(color = Palette.textSecondary, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        Text(title, style = Typography.body.copy(color = Palette.textSecondary))
    }
}

/**
 * A full-screen error with an optional retry action. The message is tagged `error:<code>`, and the retry button
 * `<Screen>.retry` by the UI tests' convention.
 */
@Composable
fun ErrorView(message: String, code: String? = null, retryTag: String = "error.retry", modifier: Modifier = Modifier, retry: (() -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.textSecondary, modifier = Modifier.size(44.dp))
        Text("Something went wrong", style = Typography.headline)
        Text(
            message,
            style = Typography.body.copy(color = Palette.textSecondary),
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(if (code != null) "error:$code" else "error.message"),
        )
        if (retry != null) {
            Button(onClick = retry, modifier = Modifier.testTag(retryTag).padding(top = 8.dp)) { Text("Try again") }
        }
    }
}

@Composable
fun EmptyStateView(title: String, message: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = Palette.textSecondary, modifier = Modifier.size(44.dp))
        Text(title, style = Typography.headline)
        Text(message, style = Typography.body.copy(color = Palette.textSecondary), textAlign = TextAlign.Center)
    }
}

/**
 * A short error line under a field or form: the design's warning icon and 12 sp red text. `code` is the error the
 * screen reports as `error=<code>`; it becomes the tag `error:<code>`, which is how a UI test asserts on the same error
 * a script does.
 */
@Composable
fun InlineError(message: String, code: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(if (code != null) "error:$code" else "inline.error"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.width(14.67.dp).height(12.67.dp))
        }
        Text(message, style = Typography.caption.copy(color = Palette.error, lineHeight = Typography.caption.fontSize * 1.25))
    }
}

/** A capsule chip: a filter, a sort or a category. `on`/`off` for UI tests. */
@Composable
fun Chip(title: String, isOn: Boolean, testTag: String, onClick: () -> Unit) {
    Text(
        title,
        style = Typography.caption.copy(color = if (isOn) Palette.onBrand else Palette.textPrimary),
        modifier = Modifier
            .testTag(testTag)
            .clip(CircleShape)
            .background(if (isOn) Palette.brand else Palette.surfaceTint)
            .clickable(role = Role.Button, onClick = onClick)
            .onOffValue(isOn)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** A thin rule between rows. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.border.copy(alpha = 0.6f)))
}

/** An outlined surface, used by the shop's list sections. */
val CardBorder = BorderStroke(1.dp, Palette.border)

/** Pads a screen's content above the keyboard. */
fun Modifier.aboveKeyboard(): Modifier = imePadding()
