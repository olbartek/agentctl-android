package io.github.olbartek.agentctl.examples.agentshop.app

import android.app.Activity
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.AgentStore
import io.github.olbartek.agentctl.examples.agentshop.clients.SessionStorage
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The release build's store: the same app on the system's environment and the app's session file, with no bridge,
 * nothing to seed and no scheduled faults.
 */
class AppStore private constructor(activity: Activity) {
    val store: AgentStore<AppFeature.State, AppFeature.Action> =
        AgentShop.store(AgentEnvironment.system(MainScope()), SessionStorage.file(File(activity.filesDir, "session.properties")))

    /** The one store: a release build has no `device relaunch`. */
    val stores: StateFlow<AgentStore<AppFeature.State, AppFeature.Action>> = MutableStateFlow(store)

    val isReady: StateFlow<Boolean> = MutableStateFlow(true)

    companion object {
        private var instance: AppStore? = null

        @Suppress("UNUSED_PARAMETER")
        fun get(activity: Activity, isFreshLaunch: Boolean): AppStore = instance ?: AppStore(activity).also { instance = it }
    }
}
