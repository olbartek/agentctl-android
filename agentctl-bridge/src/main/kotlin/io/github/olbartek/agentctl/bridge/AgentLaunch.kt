package io.github.olbartek.agentctl.bridge

import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.github.olbartek.agentctl.AgentStore
import io.github.olbartek.agentctl.MockLatency
import io.github.olbartek.agentctl.runtime.AgentLaunchOptions
import io.github.olbartek.agentctl.runtime.AgentLaunchSession
import io.github.olbartek.agentctl.runtime.AppCtlConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Starts the agent bridge in a debug build of the app and applies launch seeding (CONTRACT.md §8).
 *
 * Everything app-specific comes from the [AppCtlConfig] the host hands it — the same value its CLI runs on — so the
 * app's integration is a few lines, kept in `src/debug` (with a release twin that builds the store directly):
 *
 * ```kotlin
 * // One per process, e.g. in a singleton the activity asks for its store.
 * val launch = AgentLaunch(MyAppConfig.appCtl, activity.intent)
 * MainScope().launch { launch.start() }   // not the activity's scope: see below
 * // The activity renders launch.store once launch.isReady is true, and a splash until then.
 * ```
 *
 * The launch arguments arrive as intent extras (the CLI's `app launch` sends them with `am start`):
 * - `agent-port` (int): the bridge port (default 8765); `0` asks for any free one.
 * - `appctl-seed` (string): commands run before the first real frame; [isReady] stays false until then.
 * - `mock-latency` (int, ms): fixed mock latency (default: 300–800 ms).
 * - `clear-session` (boolean): `config.clearSession()` before the store is built.
 *
 * Build it on the main thread, once per process, and call [start] from a scope that outlives the activity. An activity
 * is recreated after a configuration change — which happens right after a cold boot, as the system's overlays
 * settle — and one tied to it would cancel a seed half-way, or start a second bridge on a port already taken.
 */
public class AgentLaunch<S, A>(config: AppCtlConfig<S, A>, intent: Intent?) {
    public val options: AgentLaunchOptions = options(intent?.extras)

    private val session = AgentLaunchSession(config, options, Dispatchers.Main.immediate) { message ->
        message.split("\n").forEach { Log.i(TAG, it) }
    }

    /** The app's live store: render it, and send the user's actions to it. */
    public val store: AgentStore<S, A> get() = session.host.store

    private val ready = MutableStateFlow(options.seed == null)

    /** `false` while a launch seed is being applied: show a splash, not the store. */
    public val isReady: StateFlow<Boolean> = ready.asStateFlow()

    /** Applies the seed (if any), then starts the bridge, so its first answer means the app is ready. Call once. */
    public suspend fun start() {
        session.start()
        ready.value = true
    }

    /** Stops the bridge, e.g. from the `Application`'s or `ViewModel`'s teardown. */
    public fun stop() {
        session.stop()
    }

    public companion object {
        private const val TAG = "AgentCtlBridge"

        /** Reads the launch extras; a value of the wrong type or out of range is ignored (CONTRACT.md §8.2). */
        public fun options(extras: Bundle?): AgentLaunchOptions {
            if (extras == null) return AgentLaunchOptions()
            val defaults = AgentLaunchOptions()
            return AgentLaunchOptions(
                port = AgentLaunchOptions.portOrNull(extras.text(AgentLaunchOptions.PORT)) ?: defaults.port,
                seed = extras.text(AgentLaunchOptions.SEED),
                latency = extras.text(AgentLaunchOptions.MOCK_LATENCY)?.toIntOrNull()?.let(MockLatency::milliseconds),
                clearSession = extras.text(AgentLaunchOptions.CLEAR_SESSION) == "true",
            )
        }

        /** Any extra as text, whether `am start` sent it with `--es`, `--ei`, `--el` or `--ez`. */
        @Suppress("DEPRECATION")
        private fun Bundle.text(key: String): String? = if (containsKey(key)) get(key)?.toString() else null
    }
}
