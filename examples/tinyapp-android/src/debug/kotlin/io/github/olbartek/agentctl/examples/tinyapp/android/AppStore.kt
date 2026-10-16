package io.github.olbartek.agentctl.examples.tinyapp.android

import android.app.Activity
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.AgentStore
import io.github.olbartek.agentctl.bridge.AgentLaunch
import io.github.olbartek.agentctl.bridge.AgentLaunchArguments
import io.github.olbartek.agentctl.examples.tinyapp.TinyApp
import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.examples.tinyapp.TinyRoot
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The debug build's store: TinyApp's live store behind the agent bridge when `tinyctl app launch` launched it (its
 * intent carries the launch's `agent-token`), and the plain app, as the release build has it, when it was launched any
 * other way. Everything app-specific comes from [TinyAppConfig.appCtl], the same value `tinyctl` runs on.
 *
 * One per process, and started in a scope of its own rather than the activity's: an activity recreated after a
 * configuration change — which happens right after a cold boot, as the system's overlays settle — must neither
 * cancel a seed half-way nor start a second bridge on the same port.
 */
class AppStore private constructor(activity: Activity) {
    private val launch = if (AgentLaunchArguments.isRequested(activity.intent)) AgentLaunch(TinyAppConfig.appCtl, activity.intent) else null

    /** The plain app's store, when no agent launched it: no bridge, nothing to seed. */
    private val plain = if (launch == null) TinyApp.store(AgentEnvironment.system(MainScope())) else null

    init {
        // Applies the seed, then starts the bridge.
        launch?.let { MainScope().launch { it.start() } }
    }

    val store: AgentStore<TinyRoot.State, TinyRoot.Action> get() = launch?.app ?: plain!!

    /** False while a launch seed is being applied. */
    val isReady: StateFlow<Boolean> = launch?.isReady ?: MutableStateFlow(true)

    companion object {
        private var instance: AppStore? = null

        fun get(activity: Activity): AppStore = instance ?: AppStore(activity).also { instance = it }
    }
}
