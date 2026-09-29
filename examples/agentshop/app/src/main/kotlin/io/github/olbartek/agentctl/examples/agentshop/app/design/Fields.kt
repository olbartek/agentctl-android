package io.github.olbartek.agentctl.examples.agentshop.app.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.olbartek.agentctl.examples.agentshop.app.R

/** What a text field holds. Drives the keyboard, autofill and capitalization. */
enum class FieldKind { PLAIN, NAME, EMAIL, ONE_TIME_CODE }

/** What a secure field holds. Drives password autofill. */
enum class SecureFieldKind { PASSWORD, NEW_PASSWORD }

/**
 * Whether the app was launched by its UI tests (the `ui-testing` launch extra), the reference's `-ui-testing`. Under
 * UI tests the fields drop their autofill hints: an autofill service would offer to save a password, or fill a
 * suggested one over what the test types. Headless runs never render a field at all.
 */
object UiTesting {
    const val EXTRA: String = "ui-testing"

    var isOn: Boolean = false
}

/**
 * A labeled form field (the design's "Input Field"): a label row with an optional trailing accessory such as a
 * "Forgot Password" link, the input, and an optional error line tagged `error:<errorCode>`.
 */
@Composable
fun FormField(
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    errorCode: String? = null,
    accessory: @Composable RowScope.() -> Unit = {},
    input: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Typography.body.copy(color = Palette.label))
            Spacer(Modifier.weight(1f).width(8.dp))
            accessory()
        }
        input()
        if (error != null) InlineError(error, code = errorCode)
    }
}

/**
 * The field's text, kept locally and handed to the store: the store's state comes back a frame later, and a field
 * that waited for it would drop keystrokes typed in between. A change from the store (a code field keeping only
 * digits, a form cleared after a reset) replaces the local text.
 */
@Composable
internal fun rememberFieldText(text: String, onChange: (String) -> Unit): Pair<TextFieldValue, (TextFieldValue) -> Unit> {
    var local by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    var lastSent by remember { mutableStateOf(text) }
    if (text != lastSent) {
        // The store changed the text by itself.
        lastSent = text
        if (text != local.text) local = TextFieldValue(text, TextRange(text.length))
    }
    return local to { value ->
        local = value
        if (value.text != lastSent) {
            lastSent = value.text
            onChange(value.text)
        }
    }
}

private fun FieldKind.keyboard(): KeyboardOptions = when (this) {
    FieldKind.PLAIN -> KeyboardOptions(imeAction = ImeAction.Done)
    FieldKind.NAME -> KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done)
    FieldKind.EMAIL -> KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Email,
        imeAction = ImeAction.Done,
    )
    FieldKind.ONE_TIME_CODE -> KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
}

private fun FieldKind.contentType(): ContentType? = when (this) {
    FieldKind.PLAIN -> null
    FieldKind.NAME -> ContentType.PersonFullName
    FieldKind.EMAIL -> ContentType.EmailAddress
    FieldKind.ONE_TIME_CODE -> ContentType.SmsOtpCode
}

/** Autofill hints, except under UI tests (see [UiTesting]). */
private fun Modifier.autofill(type: ContentType?): Modifier =
    if (type == null || UiTesting.isOn) this else semantics { contentType = type }

/** The design's text input chrome: 44 dp tall, 12 dp horizontal padding, a 1 dp border and 6 dp corners. */
private fun Modifier.fieldChrome(): Modifier = this
    .fillMaxWidth()
    .height(Metrics.fieldHeight)
    .border(1.dp, Palette.border, RoundedCornerShape(Metrics.fieldRadius))
    .background(Palette.background, RoundedCornerShape(Metrics.fieldRadius))
    .padding(horizontal = 12.dp)

/**
 * A bordered text input (the design's "Text Input"). Tagged `testTag`, with its text as [UiTestValue], which is what
 * a UI test reads for a summary key such as `AddressForm.zip`.
 */
@Composable
fun ASTextField(
    placeholder: String,
    text: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    kind: FieldKind = FieldKind.PLAIN,
    testTag: String? = null,
) {
    val (value, onValueChange) = rememberFieldText(text, onChange)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = Typography.input,
        keyboardOptions = kind.keyboard(),
        cursorBrush = SolidColor(Palette.brand),
        modifier = modifier
            .testTagIfPresent(testTag)
            .semantics { uiTestValue = text }
            .autofill(kind.contentType()),
        decorationBox = { inner ->
            Box(Modifier.fieldChrome(), contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty()) Text(placeholder, style = Typography.input.copy(color = Palette.placeholder))
                inner()
            }
        },
    )
}

/**
 * A bordered password input with a show/hide toggle. Whether the text is revealed is state owned by the screen's
 * reducer. The toggle is tagged `revealTag` (`<testTag>.reveal` when omitted), with `on`/`off`.
 */
@Composable
fun ASSecureField(
    text: String,
    onChange: (String) -> Unit,
    isRevealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "••••••••",
    kind: SecureFieldKind = SecureFieldKind.PASSWORD,
    testTag: String? = null,
    revealTag: String? = testTag?.let { "$it.reveal" },
) {
    val (value, onValueChange) = rememberFieldText(text, onChange)
    Row(modifier.fieldChrome(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Typography.input,
            visualTransformation = if (isRevealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isRevealed) KeyboardType.Text else KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            cursorBrush = SolidColor(Palette.brand),
            modifier = Modifier
                .weight(1f)
                .testTagIfPresent(testTag)
                .autofill(if (kind == SecureFieldKind.PASSWORD) ContentType.Password else ContentType.NewPassword),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) Text(placeholder, style = Typography.input.copy(color = Palette.placeholder))
                    inner()
                }
            },
        )
        Box(
            modifier = Modifier
                .testTagIfPresent(revealTag)
                .size(24.dp)
                .clickable(role = Role.Switch) { onRevealChange(!isRevealed) }
                .semantics { contentDescription = if (isRevealed) "Hide password" else "Show password" }
                .onOffValue(isRevealed),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(if (isRevealed) R.drawable.ic_eye else R.drawable.ic_eye_slash),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The design's 6-box code entry ("Enter Code"). A single, nearly transparent text field takes the input, so paste and
 * one-time-code autofill work; the boxes show one digit each, or "-".
 */
@Composable
fun CodeInputField(code: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, length: Int = 6, testTag: String? = null) {
    val (value, onValueChange) = rememberFieldText(code, onChange)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var isFocused by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Typography.input,
            keyboardOptions = FieldKind.ONE_TIME_CODE.keyboard(),
            modifier = Modifier
                .testTagIfPresent(testTag)
                .semantics { uiTestValue = code }
                .autofill(ContentType.SmsOtpCode)
                .matchParentSize()
                .alpha(0.02f)
                .focusRequester(focus)
                .onFocusChanged { isFocused = it.isFocused },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {}
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    focus.requestFocus()
                    keyboard?.show()
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val digits = Graphemes(code)
            for (index in 0 until length) {
                val digit = digits.getOrNull(index)
                val active = isFocused && index == minOf(digits.size, length - 1)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(Metrics.fieldHeight)
                        .border(1.dp, if (active) Palette.brand else Palette.border, RoundedCornerShape(Metrics.fieldRadius))
                        .background(Palette.background, RoundedCornerShape(Metrics.fieldRadius)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        digit ?: "-",
                        style = Typography.input.copy(color = if (digit == null) Palette.placeholder else Palette.textPrimary),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun Graphemes(text: String): List<String> = io.github.olbartek.agentctl.examples.agentshop.models.Graphemes.of(text)
