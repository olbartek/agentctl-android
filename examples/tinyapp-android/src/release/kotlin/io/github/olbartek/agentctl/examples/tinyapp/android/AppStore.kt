package io.github.olbartek.agentctl.examples.tinyapp.android

import android.app.Activity
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.AgentStore
import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.examples.tinyapp.TinyRoot
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The release build's store: the same app on the system's environment, with no bridge and nothing to seed. */
class AppStore private constructor() {
    val store: AgentStore<TinyRoot.State, TinyRoot.Action> = TinyAppConfig.store(AgentEnvironment.system(MainScope()))

    val isReady: StateFlow<Boolean> = MutableStateFlow(true)

    companion object {
        private var instance: AppStore? = null

        @Suppress("UNUSED_PARAMETER")
        fun get(activity: Activity): AppStore = instance ?: AppStore().also { instance = it }
    }
}
