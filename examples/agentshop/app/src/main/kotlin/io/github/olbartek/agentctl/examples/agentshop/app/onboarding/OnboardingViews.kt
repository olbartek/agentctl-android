package io.github.olbartek.agentctl.examples.agentshop.app.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.olbartek.agentctl.examples.agentshop.app.design.ASTextField
import io.github.olbartek.agentctl.examples.agentshop.app.design.BackButton
import io.github.olbartek.agentctl.examples.agentshop.app.design.FieldKind
import io.github.olbartek.agentctl.examples.agentshop.app.design.FormField
import io.github.olbartek.agentctl.examples.agentshop.app.design.FormScreen
import io.github.olbartek.agentctl.examples.agentshop.app.design.HeaderStyle
import io.github.olbartek.agentctl.examples.agentshop.app.design.InlineError
import io.github.olbartek.agentctl.examples.agentshop.app.design.LinkButton
import io.github.olbartek.agentctl.examples.agentshop.app.design.Metrics
import io.github.olbartek.agentctl.examples.agentshop.app.design.Palette
import io.github.olbartek.agentctl.examples.agentshop.app.design.PrimaryButton
import io.github.olbartek.agentctl.examples.agentshop.app.design.ScreenHeader
import io.github.olbartek.agentctl.examples.agentshop.app.design.Typography
import io.github.olbartek.agentctl.examples.agentshop.app.design.UiTesting
import io.github.olbartek.agentctl.examples.agentshop.app.design.capitalized
import io.github.olbartek.agentctl.examples.agentshop.app.design.message
import io.github.olbartek.agentctl.examples.agentshop.app.design.onOffValue
import io.github.olbartek.agentctl.examples.agentshop.app.design.screenTag
import io.github.olbartek.agentctl.examples.agentshop.app.design.summaryValue
import io.github.olbartek.agentctl.examples.agentshop.models.ProductCategory
import io.github.olbartek.agentctl.examples.agentshop.onboarding.AddressForm
import io.github.olbartek.agentctl.examples.agentshop.onboarding.AddressFormAgent
import io.github.olbartek.agentctl.examples.agentshop.onboarding.Interests
import io.github.olbartek.agentctl.examples.agentshop.onboarding.InterestsAgent
import io.github.olbartek.agentctl.examples.agentshop.onboarding.Notifications
import io.github.olbartek.agentctl.examples.agentshop.onboarding.NotificationsAgent
import io.github.olbartek.agentctl.examples.agentshop.onboarding.OnboardingFlow
import io.github.olbartek.agentctl.examples.agentshop.onboarding.Welcome
import io.github.olbartek.agentctl.examples.agentshop.onboarding.WelcomeAgent

/**
 * Onboarding's four steps, one at a time, with a back button from interests on. Steps cross-fade, except under UI
 * tests, where the outgoing step must not linger on screen.
 */
@Composable
fun OnboardingView(state: OnboardingFlow.State, send: (OnboardingFlow.Action) -> Unit) {
    BackHandler(enabled = state.step != OnboardingFlow.Step.WELCOME || state.welcome.page > 1) {
        if (state.step == OnboardingFlow.Step.WELCOME) send(OnboardingFlow.Action.Welcome(Welcome.Action.BackTapped)) else send(OnboardingFlow.Action.BackTapped)
    }
    val step: @Composable (OnboardingFlow.Step) -> Unit = { step ->
        when (step) {
            OnboardingFlow.Step.WELCOME -> WelcomeView(state.welcome) { send(OnboardingFlow.Action.Welcome(it)) }
            OnboardingFlow.Step.INTERESTS ->
                InterestsView(state.interests, onBack = { send(OnboardingFlow.Action.BackTapped) }) { send(OnboardingFlow.Action.Interests(it)) }
            OnboardingFlow.Step.ADDRESS ->
                AddressFormView(state.address, onBack = { send(OnboardingFlow.Action.BackTapped) }) { send(OnboardingFlow.Action.Address(it)) }
            OnboardingFlow.Step.NOTIFICATIONS ->
                NotificationsView(state.notifications, onBack = { send(OnboardingFlow.Action.BackTapped) }) {
                    send(OnboardingFlow.Action.Notifications(it))
                }
        }
    }
    if (UiTesting.isOn) step(state.step) else Crossfade(targetState = state.step, label = "onboarding step") { step(it) }
}

private data class WelcomePage(val icon: ImageVector, val title: String, val text: String)

private val welcomePages = listOf(
    WelcomePage(Icons.Filled.ShoppingCart, "Welcome to AgentShop", "Shoes, bags, watches and more, picked for you."),
    WelcomePage(Icons.Filled.Favorite, "Save what you love", "Favorite products and find them again in a tap."),
    WelcomePage(Icons.AutoMirrored.Filled.Send, "Fast checkout", "Your address is remembered, so an order takes seconds."),
)

@Composable
fun WelcomeView(state: Welcome.State, send: (Welcome.Action) -> Unit) {
    val page = welcomePages[minOf(state.page, welcomePages.size) - 1]
    FormScreen(Modifier.screenTag(WelcomeAgent.screenPath(state))) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.page > 1) BackButton(testTag = "Welcome.back") { send(Welcome.Action.BackTapped) }
            Spacer(Modifier.weight(1f))
            LinkButton("Skip", testTag = "Welcome.skip") { send(Welcome.Action.SkipTapped) }
        }
        Icon(
            page.icon,
            contentDescription = null,
            tint = Palette.brand,
            modifier = Modifier.padding(top = 96.dp).size(72.dp).align(Alignment.CenterHorizontally),
        )
        Text(page.title, style = Typography.screenTitle, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 40.dp))
        Text(
            page.text,
            style = Typography.body.copy(color = Palette.textSecondary),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        Text(
            "${state.page} of ${Welcome.PAGE_COUNT}",
            style = Typography.caption.copy(color = Palette.textSubtle),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp).summaryValue("Welcome.page", "${state.page}"),
        )
        PrimaryButton(
            if (state.page < Welcome.PAGE_COUNT) "Next" else "Get started",
            testTag = "Welcome.next",
            modifier = Modifier.padding(top = 48.dp),
        ) { send(Welcome.Action.NextTapped) }
    }
}

@Composable
fun InterestsView(state: Interests.State, onBack: () -> Unit, send: (Interests.Action) -> Unit) {
    FormScreen(Modifier.screenTag(InterestsAgent.screenPath(state))) {
        ScreenHeader(
            "What do you like?",
            HeaderStyle.Leading("Pick ${Interests.MINIMUM} to ${Interests.MAXIMUM} categories for your feed."),
            onBack = onBack,
        )
        // A two-column grid.
        Column(Modifier.padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (row in ProductCategory.entries.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (category in row) {
                        val isOn = category in state.selected
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("Interests.toggle.${category.code}")
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isOn) Palette.brand else Palette.surfaceTint)
                                .clickable(role = Role.Button) { send(Interests.Action.Toggled(category)) }
                                .onOffValue(isOn),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(capitalized(category.code), style = Typography.bodyMedium.copy(color = if (isOn) Palette.onBrand else Palette.textPrimary))
                        }
                    }
                }
            }
        }
        Text(
            "${state.selected.size} selected",
            style = Typography.caption.copy(color = Palette.textSubtle),
            modifier = Modifier.padding(top = 16.dp).summaryValue("Interests.selected", "${state.selected.size}"),
        )
        state.error?.let { InlineError("Pick at most ${Interests.MAXIMUM} categories.", code = it.code, modifier = Modifier.padding(top = 8.dp)) }
        PrimaryButton(
            "Continue",
            isEnabled = state.canContinue,
            testTag = "Interests.continue",
            modifier = Modifier.padding(top = Metrics.sectionSpacing),
        ) { send(Interests.Action.ContinueTapped) }
    }
}

@Composable
fun AddressFormView(state: AddressForm.State, onBack: () -> Unit, send: (AddressForm.Action) -> Unit) {
    FormScreen(Modifier.screenTag(AddressFormAgent.screenPath(state))) {
        ScreenHeader(
            "Where should we ship?",
            HeaderStyle.Leading("Saved for checkout. You can skip this and add it later."),
            onBack = onBack,
        )
        Column(Modifier.padding(top = Metrics.sectionSpacing), verticalArrangement = Arrangement.spacedBy(Metrics.fieldSpacing)) {
            FormField("Full Name") {
                ASTextField("Your name", state.address.name, { send(AddressForm.Action.NameChanged(it)) }, kind = FieldKind.NAME, testTag = "AddressForm.name")
            }
            FormField("Street") {
                ASTextField("1 Main St", state.address.street, { send(AddressForm.Action.StreetChanged(it)) }, testTag = "AddressForm.street")
            }
            FormField("City") {
                ASTextField("City", state.address.city, { send(AddressForm.Action.CityChanged(it)) }, testTag = "AddressForm.city")
            }
            FormField("Zip Code", error = state.error?.let { "Enter a five-digit zip code." }, errorCode = state.error?.code) {
                ASTextField("12345", state.address.zip, { send(AddressForm.Action.ZipChanged(it)) }, testTag = "AddressForm.zip")
            }
        }
        PrimaryButton(
            "Continue",
            isEnabled = state.canContinue,
            testTag = "AddressForm.continue",
            modifier = Modifier.padding(top = Metrics.sectionSpacing),
        ) { send(AddressForm.Action.ContinueTapped) }
        LinkButton(
            "Skip for now",
            testTag = "AddressForm.skip",
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 24.dp),
        ) { send(AddressForm.Action.SkipTapped) }
    }
}

@Composable
fun NotificationsView(state: Notifications.State, onBack: () -> Unit, send: (Notifications.Action) -> Unit) {
    FormScreen(Modifier.screenTag(NotificationsAgent.screenPath(state))) {
        ScreenHeader(
            "Stay in the loop",
            HeaderStyle.Leading("Get a notification when an order ships or a favorite goes on sale."),
            onBack = onBack,
        )
        Icon(
            Icons.Filled.Notifications,
            contentDescription = null,
            tint = Palette.brand,
            modifier = Modifier.padding(top = 64.dp).size(72.dp).align(Alignment.CenterHorizontally),
        )
        Text(
            when (state.choice) {
                null -> "Not decided yet"
                Notifications.Choice.ALLOWED -> "Notifications on"
                Notifications.Choice.OFF -> "Notifications off"
            },
            style = Typography.caption.copy(color = Palette.textSubtle),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .summaryValue("Notifications.notifications", state.choice?.code ?: "undecided"),
        )
        state.error?.let { error ->
            InlineError(error.message, code = error.code, modifier = Modifier.padding(top = 16.dp))
            PrimaryButton("Try again", isLoading = state.isLoading, testTag = "Notifications.retry", modifier = Modifier.padding(top = 16.dp)) {
                send(Notifications.Action.RetryTapped)
            }
        }
        PrimaryButton(
            "Allow notifications",
            isLoading = state.isLoading,
            testTag = "Notifications.allow",
            modifier = Modifier.padding(top = Metrics.sectionSpacing),
        ) { send(Notifications.Action.Chose(Notifications.Choice.ALLOWED)) }
        LinkButton(
            "Not now",
            testTag = "Notifications.not-now",
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 24.dp),
        ) { send(Notifications.Action.Chose(Notifications.Choice.OFF)) }
    }
}
