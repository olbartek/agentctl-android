package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.BridgeDefaults
import io.github.olbartek.agentctl.MockLatency
import io.github.olbartek.agentctl.ScreensRenderer
import io.github.olbartek.agentctl.StepFormatter
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The launch arguments of CONTRACT.md §8.2. An Android app has no command line, so on Android they arrive as
 * intent extras of the same names without the leading dash — `agent-port` (int), `appctl-seed` (string),
 * `mock-latency` (int, ms) and `clear-session` (boolean) — which is what the CLI's `app launch` sends with
 * `am start`. [parse] reads the command-line form, for a JVM host.
 */
public data class AgentLaunchOptions(
    val port: Int = BridgeDefaults.PORT,
    val seed: String? = null,
    val latency: MockLatency? = null,
    val clearSession: Boolean = false,
) {
    public companion object {
        public const val PORT: String = "agent-port"
        public const val SEED: String = "appctl-seed"
        public const val MOCK_LATENCY: String = "mock-latency"
        public const val CLEAR_SESSION: String = "clear-session"

        /** The command-line form: `-agent-port <n>`, `-appctl-seed "<script>"`, … after the executable's name. */
        public fun parse(arguments: List<String>): AgentLaunchOptions {
            var options = AgentLaunchOptions()
            val iterator = arguments.drop(1).iterator()
            while (iterator.hasNext()) {
                when (iterator.next()) {
                    "-$PORT" -> options = options.copy(port = portOrNull(iterator.nextOrNull()) ?: options.port)
                    "-$SEED" -> options = options.copy(seed = iterator.nextOrNull())
                    "-$MOCK_LATENCY" -> options = options.copy(
                        latency = iterator.nextOrNull()?.toIntOrNull()?.let(MockLatency::milliseconds),
                    )
                    "-$CLEAR_SESSION" -> options = options.copy(clearSession = true)
                }
            }
            return options
        }

        /** 0–65535, or `null`: a value that is not a port is ignored (CONTRACT.md §8.2). */
        public fun portOrNull(text: String?): Int? = text?.toIntOrNull()?.takeIf { it in 0..65535 }

        private fun Iterator<String>.nextOrNull(): String? = if (hasNext()) next() else null
    }
}

/**
 * What the in-app bridge does, independent of Android: builds the live store from the config, applies a launch
 * seed, then starts the server, so the bridge's first answer means the app is ready (CONTRACT.md §8.3).
 * `agentctl-bridge`'s `AgentLaunch` wraps it with the main thread and the activity's intent.
 */
public class AgentLaunchSession<S, A>(
    config: AppCtlConfig<S, A>,
    public val options: AgentLaunchOptions,
    /** The store's thread: `Dispatchers.Main.immediate` in an app. */
    dispatcher: CoroutineDispatcher,
    /** Where the outcome of the seed and the server goes: logcat in an app. */
    private val log: (String) -> Unit = ::println,
) {
    public val host: LiveHost<S, A>
    private val server: BridgeServer
    private val screensText: () -> String
    private var started = false

    init {
        if (options.clearSession) config.clearSession()
        host = config.makeLive(options.latency ?: MockLatency.LIVE, dispatcher)
        screensText = { ScreensRenderer.render(config.screens, config.docsText.mockExample) }
        val router = BridgeRouter(host.makeRunner(synthesizesAppearance = false), screensText)
        server = BridgeServer(dispatcher) { router.handle(it) }
    }

    /**
     * Applies the seed (if any), then starts the bridge, and returns the port it listens on, or `null` if it could
     * not listen. Call once, on the store's thread, before the first real frame is shown.
     */
    public suspend fun start(): Int? {
        if (started) return null
        started = true
        options.seed?.let { seed -> log(applySeed(seed, host.makeRunner(synthesizesAppearance = true)).log) }
        return try {
            server.start(options.port).also { log("AgentCtlBridge: listening on 127.0.0.1:$it") }
        } catch (error: IOException) {
            log("AgentCtlBridge: could not listen on port ${options.port}: $error")
            null
        }
    }

    public fun stop() {
        server.stop()
    }

    public companion object {
        /**
         * Applies a launch seed: the `(launch)` step, then the seed's commands. A seed is a script, so it fails the
         * way a script does (CONTRACT.md §5): it stops at its first failing step, and a launch that did not settle
         * fails it before its first command runs (§3.4). Returns that status and the block the app logs.
         */
        public suspend fun <S, A> applySeed(seed: String, runner: ScriptRunner<S, A>): SeedOutcome {
            val launch = runner.launch()
            val steps = mutableListOf(launch.step)
            var status = launch.status
            var message: String? = null
            if (launch.status == RunStatus.OK) {
                val result = runner.run(seed)
                steps.addAll(result.steps)
                status = result.status
                message = result.message
            }
            val outcome = if (status == RunStatus.OK) "applied" else "FAILED (exit ${status.code})"
            var log = "AgentCtlBridge: seed $outcome\n" + StepFormatter.text(steps)
            message?.let { log += "\nerror: $it" }
            return SeedOutcome(status, log)
        }
    }
}

public data class SeedOutcome(val status: RunStatus, val log: String)
