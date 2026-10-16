package io.github.olbartek.agentctl.examples.agentshop.app

import android.app.Activity
import android.content.Intent
import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.AgentStore
import io.github.olbartek.agentctl.bridge.AgentLaunch
import io.github.olbartek.agentctl.bridge.AgentLaunchArguments
import io.github.olbartek.agentctl.examples.agentshop.app.design.UiTesting
import io.github.olbartek.agentctl.examples.agentshop.clients.SessionStorage
import io.github.olbartek.agentctl.examples.agentshop.ctl.AgentShopConfig
import io.github.olbartek.agentctl.examples.agentshop.models.ScheduledFaults
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The debug build's store: AgentShop's live store behind the agent bridge when an agent launched it (`appctl app
 * launch`, or a UI test, whose intent carries the launch's `agent-token`), and the plain app, as the release build has
 * it, when it was launched any other way. Everything app-specific comes from [AgentShopConfig.appCtl], the same value
 * `shopctl` runs on, given the app's session file (which the `clear-session` extra empties) and the calls a UI test
 * fails (the `mock-fault` extras).
 *
 * One per process, and started in a scope of its own rather than the activity's: an activity recreated after a
 * configuration change must neither cancel a seed half-way nor start a second bridge on the same port. The one
 * exception is a UI test (the `ui-testing` extra): its tests share a process, so each fresh launch gets a fresh store,
 * as a relaunched app would.
 */
class AppStore private constructor(activity: Activity) {
    private val launch = if (AgentLaunchArguments.isRequested(activity.intent)) {
        AgentLaunch(
            AgentShopConfig.appCtl(SessionStorage.file(sessionFile(activity)), ScheduledFaults(faults(activity.intent))),
            activity.intent,
        )
    } else {
        null
    }

    /** The plain app's store, when no agent launched it: no bridge, nothing to seed, no scheduled faults. */
    private val plain = if (launch == null) {
        MutableStateFlow(AgentShop.store(AgentEnvironment.system(MainScope()), SessionStorage.file(sessionFile(activity))))
    } else {
        null
    }

    /** Where the seed is applied and the bridge started; cancelled with the store when a UI test relaunches. */
    private val startup = MainScope()

    init {
        // Applies the seed, then starts the bridge.
        launch?.let { startup.launch { it.start() } }
    }

    /** Ends this store: the bridge, the store's effects and a seed still being applied. */
    private fun close() {
        startup.cancel()
        launch?.stop()
    }

    val store: AgentStore<AppFeature.State, AppFeature.Action> get() = stores.value

    /** The store over time: `device relaunch` replaces it with a fresh one. */
    val stores: StateFlow<AgentStore<AppFeature.State, AppFeature.Action>> = launch?.apps ?: plain!!

    /** False while a launch seed is being applied. */
    val isReady: StateFlow<Boolean> = launch?.isReady ?: MutableStateFlow(true)

    companion object {
        private var instance: AppStore? = null

        fun get(activity: Activity, isFreshLaunch: Boolean): AppStore {
            if (isFreshLaunch && UiTesting.isOn) {
                instance?.close()
                instance = null
            }
            return instance ?: AppStore(activity).also { instance = it }
        }

        /**
         * Whether the UI tests launched the app (the `ui-testing` extra): only with the launch's `agent-token`, as for
         * every other launch extra, since the UI tests pass one.
         */
        fun isUiTesting(intent: Intent?): Boolean =
            AgentLaunchArguments.isRequested(intent) && intent?.getBooleanExtra(UiTesting.EXTRA, false) == true

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
