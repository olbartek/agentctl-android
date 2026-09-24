package io.github.olbartek.agentctl.examples.tinyapp.android

import android.app.Activity
import io.github.olbartek.agentctl.AgentStore
import io.github.olbartek.agentctl.bridge.AgentLaunch
import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.examples.tinyapp.TinyRoot
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The debug build's store: TinyApp's live store behind the agent bridge. Everything app-specific comes from
 * [TinyAppConfig.appCtl], the same value `tinyctl` runs on.
 *
 * One per process, and started in a scope of its own rather than the activity's: an activity recreated after a
 * configuration change — which happens right after a cold boot, as the system's overlays settle — must neither
 * cancel a seed half-way nor start a second bridge on the same port.
 */
class AppStore private constructor(activity: Activity) {
    private val launch = AgentLaunch(TinyAppConfig.appCtl, activity.intent)

    init {
        // Applies the seed, then starts the bridge.
        MainScope().launch { launch.start() }
    }

    val store: AgentStore<TinyRoot.State, TinyRoot.Action> get() = launch.store

    /** False while a launch seed is being applied. */
    val isReady: StateFlow<Boolean> get() = launch.isReady

    companion object {
        private var instance: AppStore? = null

        fun get(activity: Activity): AppStore = instance ?: AppStore(activity).also { instance = it }
    }
}
