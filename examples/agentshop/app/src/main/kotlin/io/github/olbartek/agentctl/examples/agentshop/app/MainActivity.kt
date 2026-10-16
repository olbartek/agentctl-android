package io.github.olbartek.agentctl.examples.agentshop.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import io.github.olbartek.agentctl.examples.agentshop.app.design.AgentShopTheme
import io.github.olbartek.agentctl.examples.agentshop.app.design.LoadingView
import io.github.olbartek.agentctl.examples.agentshop.app.design.Palette
import io.github.olbartek.agentctl.examples.agentshop.app.design.UiTesting
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * AgentShop's one activity: [RootView] over the store [AppStore] keeps — behind the agent bridge in a debug build, and
 * built directly in a release build — with a splash while a launch seed is applied. It also opens AgentShop's
 * `agentshop://` links (`app openurl`, a tapped link), as `device open-url` does in a script.
 */
class MainActivity : ComponentActivity() {
    private lateinit var app: AppStore

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UiTesting.isOn = AppStore.isUiTesting(intent)
        app = AppStore.get(this, isFreshLaunch = savedInstanceState == null)
        // Not again when the activity is only recreated (a rotation): the link was opened the first time.
        if (savedInstanceState == null) open(intent)
        setContent {
            AgentShopTheme {
                // Test tags are resource-ids too, for uiautomator and screenshot tools.
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    val ready by app.isReady.collectAsState()
                    if (ready) {
                        val store by app.stores.collectAsState()
                        // Keyed by the store, so `device relaunch` (a new store) shows a fresh view tree, as a relaunch would.
                        key(store) {
                            val state by store.states.collectAsState()
                            RootView(state, store::send)
                        }
                    } else {
                        Box(Modifier.fillMaxSize().background(Palette.background), contentAlignment = Alignment.Center) { LoadingView() }
                    }
                }
            }
        }
    }

    /** A link while the app is in front (`singleTop`). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    /**
     * Sends the action [AppFeature.actionOpening] maps the intent's link to, once the store is ready (after a launch
     * seed), to the store shown then. A link the app does not handle does nothing.
     */
    private fun open(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        val url = try {
            URI(link)
        } catch (_: URISyntaxException) {
            return
        }
        val action = AppFeature.actionOpening(url) ?: return
        val app = app
        // The process's scope, like the store's: a link is not dropped by a configuration change mid-seed.
        linkScope.launch {
            app.isReady.first { it }
            app.stores.value.send(action)
        }
    }

    private companion object {
        val linkScope = MainScope()
    }
}
