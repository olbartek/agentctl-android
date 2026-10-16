package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.AgentNumbers
import io.github.olbartek.agentctl.BridgeDefaults
import io.github.olbartek.agentctl.MockLatency
import io.github.olbartek.agentctl.ScreensRenderer
import io.github.olbartek.agentctl.StepFormatter
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The launch arguments of CONTRACT.md §8.2. An Android app has no command line, so on Android they arrive as
 * intent extras of the same names without the leading dash — `agent-port` (int), `appctl-seed` (string),
 * `mock-latency` (int, ms), `clear-session` (boolean) and `agent-token` (string) — which is what the CLI's
 * `app launch` sends with `am start`. [parse] reads the command-line form, for a JVM host.
 *
 * The token is what makes the rest count: a launch without a well-formed one ([isToken]) is not the CLI's, so its
 * other arguments are ignored and an [AgentLaunchSession] starts no bridge.
 */
public data class AgentLaunchOptions(
    val port: Int = BridgeDefaults.PORT,
    val seed: String? = null,
    val latency: MockLatency? = null,
    val clearSession: Boolean = false,
    /**
     * The launch's token, which every request to the bridge must carry in `X-Appctl-Token`: 32 lowercase hex
     * characters, fresh for each launch. `null` without one.
     */
    val token: String? = null,
) {
    /** Options without a token: the constructor before 0.9, kept so code built against it still links. */
    public constructor(
        port: Int = BridgeDefaults.PORT,
        seed: String? = null,
        latency: MockLatency? = null,
        clearSession: Boolean = false,
    ) : this(port, seed, latency, clearSession, null)

    public companion object {
        /** The bridge port's name: the `agent-port` extra, `-agent-port` on a command line. */
        public const val PORT: String = "agent-port"

        /** The seed's name: the `appctl-seed` extra, `-appctl-seed` on a command line. */
        public const val SEED: String = "appctl-seed"

        /** The mock latency's name, in ms: the `mock-latency` extra, `-mock-latency` on a command line. */
        public const val MOCK_LATENCY: String = "mock-latency"

        /** The session-clearing flag's name: the `clear-session` extra, `-clear-session` on a command line. */
        public const val CLEAR_SESSION: String = "clear-session"

        /** The launch token's name: the `agent-token` extra, `-agent-token` on a command line. */
        public const val TOKEN: String = "agent-token"

        /**
         * The command-line form: `-agent-port <n>`, `-appctl-seed "<script>"`, …, `-agent-token <token>` after the
         * executable's name. Without a well-formed token every argument is ignored: the defaults.
         */
        public fun parse(arguments: List<String>): AgentLaunchOptions {
            var options = AgentLaunchOptions()
            val iterator = arguments.drop(1).iterator()
            while (iterator.hasNext()) {
                when (iterator.next()) {
                    "-$PORT" -> options = options.copy(port = portOrNull(iterator.nextOrNull()) ?: options.port)
                    "-$SEED" -> options = options.copy(seed = iterator.nextOrNull())
                    "-$MOCK_LATENCY" -> options = options.copy(
                        latency = iterator.nextOrNull()?.let(AgentNumbers::int)?.let(MockLatency::milliseconds),
                    )
                    "-$CLEAR_SESSION" -> options = options.copy(clearSession = true)
                    "-$TOKEN" -> options = options.copy(token = iterator.nextOrNull())
                }
            }
            return options.honored()
        }

        /**
         * Whether a launch with these command-line [arguments] asks for the bridge: it carries a well-formed
         * `-agent-token`, which the CLI always passes and any other launch does not. The JVM form of
         * `AgentLaunchArguments.isRequested`.
         */
        public fun isRequested(arguments: List<String>): Boolean = parse(arguments).token != null

        /** Whether [text] is a well-formed launch token: exactly 32 lowercase hex characters. */
        public fun isToken(text: String?): Boolean =
            text != null && text.length == TOKEN_LENGTH && text.all { it in '0'..'9' || it in 'a'..'f' }

        /**
         * A fresh launch token: 128 bits from [SecureRandom], as 32 lowercase hex characters. The CLI makes one for
         * each launch; a test runner that launches the app itself makes its own.
         */
        public fun makeToken(): String {
            val bytes = ByteArray(TOKEN_LENGTH / 2).also(random::nextBytes)
            val hex = "0123456789abcdef"
            return buildString { bytes.forEach { append(hex[(it.toInt() shr 4) and 0xf]).append(hex[it.toInt() and 0xf]) } }
        }

        private const val TOKEN_LENGTH = 32

        private val random by lazy { SecureRandom() }

        /**
         * 0–65535, or `null`: a value that is not a port is ignored (CONTRACT.md §8.2). Read as Swift's `UInt16(_:)`
         * reads it: a sign, then ASCII digits only.
         */
        public fun portOrNull(text: String?): Int? = text?.let(AgentNumbers::int)?.takeIf { it in 0..65535 }

        private fun Iterator<String>.nextOrNull(): String? = if (hasNext()) next() else null
    }

    /** The options, with the token left out (`<token>` when there is one): it is not for logs. */
    override fun toString(): String =
        "AgentLaunchOptions(port=$port, seed=$seed, latency=$latency, clearSession=$clearSession, " +
            "token=${if (token == null) "null" else "<token>"})"

    /** These options if their token is well-formed, else the defaults: without a token, every argument is ignored. */
    internal fun honored(): AgentLaunchOptions = if (isToken(token)) this else AgentLaunchOptions()
}

/**
 * What the in-app bridge does, independent of Android: builds the live store from the config, applies a launch
 * seed, then starts the server, so the bridge's first answer means the app is ready (CONTRACT.md §8.3).
 * `agentctl-bridge`'s `AgentLaunch` wraps it with the main thread and the activity's intent.
 *
 * Options without a well-formed token are not the CLI's: the session ignores them all (no seed, no session cleared, no
 * mock latency) and [start] starts no bridge. With one, the bridge answers only requests that carry it.
 */
public class AgentLaunchSession<D : AgentDriver<A, *>, A>(
    config: AppCtlConfig<D, A>,
    options: AgentLaunchOptions,
    /** The store's thread: `Dispatchers.Main.immediate` in an app. */
    dispatcher: CoroutineDispatcher,
    /** Where the outcome of the seed and the server goes: logcat in an app. */
    private val log: (String) -> Unit = ::println,
) {
    /** The options the session follows: those it was given, or the defaults when they carry no well-formed token. */
    public val options: AgentLaunchOptions = options.honored()

    /** The live host built from the config: the app the seed and the bridge drive. */
    public val host: LiveHost<D, A>
    /** The bridge, checking the launch's token; `null` without one: no token, no bridge (CONTRACT.md §8.2). */
    private val server: BridgeServer?
    private val screensText: () -> String

    /** Made after the seed, so its runner drives the store the seed left (a seed's `device relaunch` replaces it). */
    private lateinit var router: BridgeRouter<A>
    private var started = false

    init {
        if (this.options.clearSession) config.clearSession()
        host = config.makeLive(this.options.latency ?: MockLatency.LIVE, dispatcher)
        val device = config.deviceConfig.surface
        screensText = { ScreensRenderer.render(config.screens, config.docsText.mockExample, device) }
        server = this.options.token?.let { token ->
            BridgeServer(dispatcher, config.applicationId, BridgeDefaults.PLATFORM, token) { router.handle(it) }
        }
    }

    /**
     * Applies the seed (if any), then starts the bridge, and returns the port it listens on, or `null` if it could
     * not listen or the launch had no token. Call once, on the store's thread, before the first real frame is shown.
     */
    public suspend fun start(): Int? {
        if (started) return null
        started = true
        if (server == null) {
            log("AgentCtlBridge: no agent-token; not listening")
            return null
        }
        options.seed?.let { seed -> log(applySeed(seed, host.makeRunner(synthesizesAppearance = true)).log) }
        router = BridgeRouter(host.makeRunner(synthesizesAppearance = false), screensText)
        return try {
            server.start(options.port).also { log("AgentCtlBridge: listening on 127.0.0.1:$it") }
        } catch (error: IOException) {
            log("AgentCtlBridge: could not listen on port ${options.port}: $error")
            null
        }
    }

    /** Stops the bridge and cancels the live store's running effects: the session is over. */
    public fun stop() {
        server?.stop()
        host.close()
    }

    public companion object {
        /**
         * Applies a launch seed: the `(launch)` step, then the seed's commands. A seed is a script, so it fails the
         * way a script does (CONTRACT.md §5): it stops at its first failing step, and a launch that did not settle
         * fails it before its first command runs (§3.4). Returns that status and the block the app logs.
         */
        public suspend fun <A> applySeed(seed: String, runner: ScriptRunner<A>): SeedOutcome {
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

/** What [AgentLaunchSession.applySeed] returns: the seed's exit status and the block the app logs. */
public data class SeedOutcome(val status: RunStatus, val log: String)
