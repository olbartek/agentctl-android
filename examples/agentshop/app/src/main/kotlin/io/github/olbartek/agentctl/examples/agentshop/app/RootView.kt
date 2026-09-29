package io.github.olbartek.agentctl.examples.agentshop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.olbartek.agentctl.examples.agentshop.app.auth.AuthFlowView
import io.github.olbartek.agentctl.examples.agentshop.app.design.LoadingView
import io.github.olbartek.agentctl.examples.agentshop.app.design.Palette
import io.github.olbartek.agentctl.examples.agentshop.app.design.screenTag
import io.github.olbartek.agentctl.examples.agentshop.app.home.HomeTabsView
import io.github.olbartek.agentctl.examples.agentshop.app.onboarding.OnboardingView

/**
 * The app by [AppFeature.State]'s case, as the reference's `RootView`: the launching screen (which restores the saved
 * session when it appears), then auth, onboarding or home.
 */
@Composable
fun RootView(state: AppFeature.State, send: (AppFeature.Action) -> Unit) {
    when (state) {
        AppFeature.State.Launching -> {
            // Keyed by the case, so a `reset` back to launching appears (and restores the session) again.
            key(state) {
                LaunchedEffect(Unit) { send(AppFeature.Action.Appeared) }
            }
            Box(Modifier.fillMaxSize().background(Palette.background).screenTag("launching"), contentAlignment = Alignment.Center) {
                LoadingView("Starting…")
            }
        }
        is AppFeature.State.Auth -> AuthFlowView(state.state) { send(AppFeature.Action.Auth(it)) }
        is AppFeature.State.Onboarding -> OnboardingView(state.state) { send(AppFeature.Action.Onboarding(it)) }
        // A new home id (a new sign-in) rebuilds the tabs, so their screens appear afresh.
        is AppFeature.State.Home -> key(state.state.id) { HomeTabsView(state.state) { send(AppFeature.Action.Home(it)) } }
    }
}
