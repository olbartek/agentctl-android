package io.github.olbartek.agentctl.examples.agentshop.app

import android.app.Activity
import android.content.Intent
import io.github.olbartek.agentctl.AgentStore
import io.github.olbartek.agentctl.bridge.AgentLaunch
import io.github.olbartek.agentctl.examples.agentshop.app.design.UiTesting
import io.github.olbartek.agentctl.examples.agentshop.clients.SessionStorage
import io.github.olbartek.agentctl.examples.agentshop.ctl.AgentShopConfig
import io.github.olbartek.agentctl.examples.agentshop.models.ScheduledFaults
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The debug build's store: AgentShop's live store behind the agent bridge. Everything app-specific comes from
 * [AgentShopConfig.appCtl], the same value `shopctl` runs on, given the app's session file (which the `clear-session`
 * extra empties) and the calls a UI test fails (the `mock-fault` extras).
 *
 * One per process, and started in a scope of its own rather than the activity's: an activity recreated after a
 * configuration change must neither cancel a seed half-way nor start a second bridge on the same port. The one
 * exception is a UI test (the `ui-testing` extra): its tests share a process, so each fresh launch gets a fresh store,
 * as a relaunched app would.
 */
class AppStore private constructor(activity: Activity) {
    private val launch = AgentLaunch(
        AgentShopConfig.appCtl(SessionStorage.file(sessionFile(activity)), ScheduledFaults(faults(activity.intent))),
        activity.intent,
    )

    init {
        // Applies the seed, then starts the bridge.
        MainScope().launch { launch.start() }
    }

    val store: AgentStore<AppFeature.State, AppFeature.Action> get() = launch.store

    /** False while a launch seed is being applied. */
    val isReady: StateFlow<Boolean> get() = launch.isReady

    companion object {
        private var instance: AppStore? = null

        fun get(activity: Activity, isFreshLaunch: Boolean): AppStore {
            if (isFreshLaunch && UiTesting.isOn) {
                instance?.launch?.stop()
                instance = null
            }
            return instance ?: AppStore(activity).also { instance = it }
        }

        /** `mock-fault` specs (`orders.placeOrder#1=network`): a string array, or one string. */
        private fun faults(intent: Intent?): List<String> {
            val extras = intent?.extras ?: return emptyList()
            extras.getStringArray(ScheduledFaults.ARGUMENT)?.let { return it.toList() }
            return listOfNotNull(extras.getString(ScheduledFaults.ARGUMENT))
        }
    }
}

/** Where the session is kept, so a relaunch restores it ("Keep me signed in"). */
internal fun sessionFile(activity: Activity): File = File(activity.filesDir, "session.properties")
