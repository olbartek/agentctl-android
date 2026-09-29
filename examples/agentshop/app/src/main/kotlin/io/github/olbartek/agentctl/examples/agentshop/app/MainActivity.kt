package io.github.olbartek.agentctl.examples.agentshop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import io.github.olbartek.agentctl.examples.agentshop.app.design.AgentShopTheme
import io.github.olbartek.agentctl.examples.agentshop.app.design.LoadingView
import io.github.olbartek.agentctl.examples.agentshop.app.design.Palette
import io.github.olbartek.agentctl.examples.agentshop.app.design.UiTesting

/**
 * AgentShop's one activity: [RootView] over the store [AppStore] keeps — behind the agent bridge in a debug build, and
 * built directly in a release build — with a splash while a launch seed is applied.
 */
class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UiTesting.isOn = intent.getBooleanExtra(UiTesting.EXTRA, false)
        val app = AppStore.get(this, isFreshLaunch = savedInstanceState == null)
        setContent {
            AgentShopTheme {
                // Test tags are resource-ids too, for uiautomator and screenshot tools.
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    val ready by app.isReady.collectAsState()
                    if (ready) {
                        val state by app.store.states.collectAsState()
                        RootView(state, app.store::send)
                    } else {
                        Box(Modifier.fillMaxSize().background(Palette.background), contentAlignment = Alignment.Center) { LoadingView() }
                    }
                }
            }
        }
    }
}
