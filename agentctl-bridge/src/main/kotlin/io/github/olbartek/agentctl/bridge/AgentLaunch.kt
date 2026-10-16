package io.github.olbartek.agentctl.bridge

import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.github.olbartek.agentctl.AgentNumbers
import io.github.olbartek.agentctl.MockLatency
import io.github.olbartek.agentctl.runtime.AgentDriver
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
 * app's integration is a few lines, kept in `src/debug` (with a release twin that builds the app directly):
 *
 * ```kotlin
 * // One per process, e.g. in a singleton the activity asks for its app.
 * val launch = AgentLaunch(MyAppConfig.appCtl, activity.intent)
 * MainScope().launch { launch.start() }   // not the activity's scope: see below
 * // The activity renders launch.apps' latest (the store, or the coordinator) once launch.isReady is true, and a
 * // splash until then.
 * ```
 *
 * The launch arguments arrive as intent extras (the CLI's `app launch` sends them with `am start`):
 * - `agent-token` (string): the launch's token, 32 lowercase hex characters, which every request to the bridge must
 *   carry. Without a well-formed one the launch is not the CLI's: every other extra is ignored, and no bridge starts.
 * - `agent-port` (int): the bridge port (default 8765); `0` asks for any free one.
 * - `appctl-seed` (string): commands run before the first real frame; [isReady] stays false until then.
 * - `mock-latency` (int, ms): fixed mock latency (default: 300–800 ms).
 * - `clear-session` (boolean): `config.clearSession()` before the app is built.
 *
 * Build it only when [AgentLaunchArguments.isRequested] (a launch by hand, from the launcher or Android Studio, then
 * runs the plain app), on the main thread, once per process, and call [start] from a scope that outlives the activity. An activity
 * is recreated after a configuration change — which happens right after a cold boot, as the system's overlays
 * settle — and one tied to it would cancel a seed half-way, or start a second bridge on a port already taken.
 */
public class AgentLaunch<D : AgentDriver<A, App>, A, App>(config: AppCtlConfig<D, A>, intent: Intent?) {
    /** The launch arguments read from the intent's extras; the defaults for an intent without a well-formed token. */
    public val options: AgentLaunchOptions = options(intent?.extras)

    private val session = AgentLaunchSession(config, options, Dispatchers.Main.immediate) { message ->
        message.split("\n").forEach { Log.i(TAG, it) }
    }

    private val driver: D = session.host.driver
    private val currentApp = MutableStateFlow(driver.app)

    init {
        // Compose's idleness, unless the config's live host brings a check of its own.
        if (session.host.isUIIdle == null) session.host.isUIIdle = ComposeUI::isIdle
        driver.onRelaunch = { currentApp.value = driver.app }
    }

    /**
     * What the app's root shows — the store of a store app, the coordinator of an MVVM app: render it, and send the
     * user's actions to it. `device relaunch` replaces it.
     */
    public val app: App get() = currentApp.value

    /**
     * [app] over time: a new one after each `device relaunch`, which starts the app's state over. An app whose device
     * configuration has `relaunch` renders the latest, e.g. `launch.apps.collectAsState().value`.
     */
    public val apps: StateFlow<App> = currentApp.asStateFlow()

    /** The name [app] had before 0.7, when every app was a store. */
    @Deprecated("Not every app is a store since 0.7: use app.", ReplaceWith("app"))
    public val store: App get() = app

    /** The name [apps] had before 0.7. */
    @Deprecated("Not every app is a store since 0.7: use apps.", ReplaceWith("apps"))
    public val stores: StateFlow<App> get() = apps

    /**
     * Whether the app's UI is idle — no navigation transition or animation in flight — which settling waits for
     * (CONTRACT.md §8.5). Called on the main thread. Default: Compose's idleness (no recomposition pending and no
     * frame awaited), which is always idle in an app without Compose. Add your own UI's transitions to it (Views,
     * say), or replace it:
     *
     * ```kotlin
     * val compose = launch.isUIIdle
     * launch.isUIIdle = { compose?.invoke() != false && !navigator.isTransitioning }
     * ```
     */
    public var isUIIdle: (() -> Boolean)?
        get() = session.host.isUIIdle
        set(value) {
            session.host.isUIIdle = value
        }

    private val ready = MutableStateFlow(options.seed == null)

    /** `false` while a launch seed is being applied: show a splash, not the store. */
    public val isReady: StateFlow<Boolean> = ready.asStateFlow()

    /**
     * Applies the seed (if any), then starts the bridge, so its first answer means the app is ready. Call once. Without
     * a well-formed `agent-token` it starts nothing, and logs `AgentCtlBridge: no agent-token; not listening`.
     */
    public suspend fun start() {
        session.start()
        ready.value = true
    }

    /**
     * Stops the bridge and cancels the store's running effects, e.g. from the `Application`'s or `ViewModel`'s
     * teardown. The store is unusable afterwards.
     */
    public fun stop() {
        session.stop()
    }

    public companion object {
        private const val TAG = "AgentCtlBridge"

        /** [AgentLaunchArguments.isRequested], under the name it had before 0.7. */
        public fun isRequested(intent: Intent?): Boolean = AgentLaunchArguments.isRequested(intent)

        /**
         * Reads the launch extras; a value of the wrong type or out of range is ignored (CONTRACT.md §8.2), and so is
         * every extra when there is no well-formed `agent-token`: the defaults.
         */
        public fun options(extras: Bundle?): AgentLaunchOptions {
            if (extras == null) return AgentLaunchOptions()
            val token = extras.text(AgentLaunchOptions.TOKEN)
            if (!AgentLaunchOptions.isToken(token)) return AgentLaunchOptions()
            val defaults = AgentLaunchOptions()
            return AgentLaunchOptions(
                port = AgentLaunchOptions.portOrNull(extras.text(AgentLaunchOptions.PORT)) ?: defaults.port,
                seed = extras.text(AgentLaunchOptions.SEED),
                latency = extras.text(AgentLaunchOptions.MOCK_LATENCY)?.let(AgentNumbers::int)?.let(MockLatency::milliseconds),
                clearSession = extras.text(AgentLaunchOptions.CLEAR_SESSION) == "true",
                token = token,
            )
        }
    }
}

/** The launch extras the CLI's `app` subcommands send, read without naming an app's driver. */
public object AgentLaunchArguments {
    /** The launch token's extra, `agent-token`: a string of 32 lowercase hex characters. */
    public const val TOKEN: String = AgentLaunchOptions.TOKEN

    /**
     * Whether this launch asks for the bridge: it carries a well-formed `agent-token` extra, which the CLI's `app`
     * subcommands always send and any other launch (the launcher's icon, Android Studio's Run) does not. An app that
     * should run as the plain app over its live dependencies unless an agent launched it checks this before creating
     * an [AgentLaunch]:
     *
     * ```kotlin
     * if (AgentLaunchArguments.isRequested(activity.intent)) { … AgentLaunch(MyAppConfig.appCtl, activity.intent) … } else { … }
     * ```
     */
    public fun isRequested(intent: Intent?): Boolean = AgentLaunchOptions.isToken(intent?.extras?.text(TOKEN))

    /**
     * A fresh launch token, for a test runner that launches the app itself (an instrumented test's `Intent`): pass it
     * as the `agent-token` extra, and send it as `X-Appctl-Token` with every request to the bridge. 128 bits from
     * `SecureRandom`, as 32 lowercase hex characters; make a new one for each launch.
     *
     * ```kotlin
     * intent.putExtra(AgentLaunchArguments.TOKEN, AgentLaunchArguments.makeToken())
     * ```
     */
    public fun makeToken(): String = AgentLaunchOptions.makeToken()
}

/** Any extra as text, whether `am start` sent it with `--es`, `--ei`, `--el` or `--ez`. */
@Suppress("DEPRECATION")
private fun Bundle.text(key: String): String? = if (containsKey(key)) get(key)?.toString() else null
