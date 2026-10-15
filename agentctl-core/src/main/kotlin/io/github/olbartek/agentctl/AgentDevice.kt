package io.github.olbartek.agentctl

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** A permission's status, as the app's permission fakes report it (CONTRACT.md §2.4). */
public enum class PermissionStatus(
    /** How scripts and steps spell it, e.g. `not-determined`. */
    public val rawValue: String,
) {
    /** Never asked: a request shows the prompt. */
    NOT_DETERMINED("not-determined"),

    /** Allowed. */
    GRANTED("granted"),

    /** Refused. */
    DENIED("denied"),

    /** Allowed in part, e.g. some photos or an approximate location. */
    LIMITED("limited"),
    ;

    public companion object {
        /** The status spelled [rawValue], or `null`. */
        public fun of(rawValue: String): PermissionStatus? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** What a biometric authentication (a fingerprint, face unlock) came to. */
public enum class BiometryResult {
    /** The user was recognised. */
    SUCCESS,

    /** The user was not recognised, or the prompt was withdrawn. */
    FAILURE,

    /** No biometry is enrolled, so no prompt was shown. */
    UNAVAILABLE,
}

/** Whether the app is in front. */
public enum class DevicePhase(
    /** How scripts spell it: `foreground` or `background`. */
    public val rawValue: String,
) {
    /** In front: screen commands work. */
    FOREGROUND("foreground"),

    /** Behind something else: screen commands fail until `device foreground`. */
    BACKGROUND("background"),
}

/** A position on Earth, in degrees. */
public data class DeviceLocation(
    /** Degrees north, from -90 to 90. */
    val latitude: Double,
    /** Degrees east, from -180 to 180. */
    val longitude: Double,
) {
    public companion object {
        private val DECIMAL = Regex("-?[0-9]+(\\.[0-9]+)?")

        /**
         * `<lat>,<lon>`: each an optional `-`, digits, and an optional `.` and digits; latitude within [-90, 90],
         * longitude within [-180, 180]; no spaces (CONTRACT.md §2.4). `null` for anything else.
         */
        public fun parse(text: String): DeviceLocation? {
            val parts = text.split(",")
            if (parts.size != 2 || !parts.all(DECIMAL::matches)) return null
            val latitude = parts[0].toDouble()
            val longitude = parts[1].toDouble()
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            return DeviceLocation(latitude, longitude)
        }

        /** `device location`'s argument: a location, or `none` (a [Setting] of `null`); `null` when it is neither. */
        public fun parseArgument(text: String): Setting? =
            if (text == "none") Setting(null) else parse(text)?.let(::Setting)
    }

    /** A location to set, or `null` to remove it. */
    public data class Setting(val location: DeviceLocation?)
}

/**
 * The simulated device an app's environment fakes read: permissions and their system prompts, connectivity,
 * location, biometry and whether the app is in front. The `device` runtime command changes it (CONTRACT.md §2.4).
 *
 * One device per host, as [AgentEnvironment.device]. The app routes its own clients' fakes through it, as it routes
 * backend calls through [MockBackend.call]:
 *
 * ```kotlin
 * class FakeNotifications(private val device: AgentDevice) : Notifications {
 *     override fun status() = device.permission("notifications")
 *     override suspend fun request() = device.requestPermission("notifications")
 * }
 * ```
 *
 * Outside a host — a preview, a release build of a mock-backed app — the device is *undriven*: online, no location,
 * biometry enrolled, and every request answered at once, as if the user allowed it, so nothing ever waits for a
 * prompt no one can answer. A host's device is *driven*: a request shows a prompt that only `device prompt` (or
 * `device biometry`) answers.
 *
 * @param isDriven `true` for a host's device, whose requests show prompts the `device` command answers.
 * @param offlineErrorCode the code mocked calls fail with while offline ([AgentDeviceConfig.offlineErrorCode]).
 */
public class AgentDevice(
    public val isDriven: Boolean = false,
    public val offlineErrorCode: String? = null,
) {
    private sealed interface Prompt {
        val name: String

        data class Permission(val service: String) : Prompt {
            override val name: String get() = service
        }

        data object Biometry : Prompt {
            override val name: String get() = "biometry"
        }
    }

    private sealed interface Answer {
        data class Permission(val status: PermissionStatus) : Answer
        data class Biometry(val result: BiometryResult) : Answer
    }

    private class Waiting(val prompt: Prompt, val continuation: CancellableContinuation<Answer>)

    private val lock = Any()
    private val statuses = mutableMapOf<String, PermissionStatus>()
    private val waiting = mutableListOf<Waiting>()
    private var online = true
    private var currentLocation: DeviceLocation? = null
    private var biometryEnrolled = true
    private var currentPhase = DevicePhase.FOREGROUND
    private val onlineObservers = mutableListOf<SendChannel<Boolean>>()
    private val locationObservers = mutableListOf<SendChannel<DeviceLocation?>>()

    // What the app's fakes read

    /** The permission's current status. A service nothing has set is `not-determined`. */
    public fun permission(service: String): PermissionStatus =
        synchronized(lock) { statuses[service] ?: PermissionStatus.NOT_DETERMINED }

    /**
     * Requests a permission. When its status is `not-determined`, a system prompt opens and this waits for the answer
     * (`device prompt allow|deny|limited`); otherwise it returns the current status at once. An undriven device grants
     * it at once. Cancelling the waiting coroutine withdraws the prompt.
     */
    public suspend fun requestPermission(service: String): PermissionStatus {
        val answer = waitForAnswer(Prompt.Permission(service)) {
            val status = statuses[service] ?: PermissionStatus.NOT_DETERMINED
            when {
                status != PermissionStatus.NOT_DETERMINED -> Answer.Permission(status)
                !isDriven -> {
                    statuses[service] = PermissionStatus.GRANTED
                    Answer.Permission(PermissionStatus.GRANTED)
                }
                else -> null
            }
        }
        return (answer as? Answer.Permission)?.status ?: permission(service)
    }

    /** Whether the device has a network connection. Offline, every [MockBackend.call] fails. */
    public val isOnline: Boolean get() = synchronized(lock) { online }

    /** The connection: the current value first, then each change. */
    public fun onlineUpdates(): Flow<Boolean> = observe(onlineObservers) { online }

    /** The device's location, or `null` when it has none. */
    public val location: DeviceLocation? get() = synchronized(lock) { currentLocation }

    /** The location: the current value first, then each change. */
    public fun locationUpdates(): Flow<DeviceLocation?> = observe(locationObservers) { currentLocation }

    /** Whether a fingerprint or face is enrolled. */
    public val isBiometryEnrolled: Boolean get() = synchronized(lock) { biometryEnrolled }

    /**
     * Authenticates with biometry: [BiometryResult.UNAVAILABLE] at once when none is enrolled; otherwise a biometric
     * prompt opens and this waits for `device biometry match|fail`. An undriven device succeeds at once.
     */
    public suspend fun authenticateBiometry(): BiometryResult {
        val answer = waitForAnswer(Prompt.Biometry) {
            when {
                !biometryEnrolled -> Answer.Biometry(BiometryResult.UNAVAILABLE)
                isDriven -> null
                else -> Answer.Biometry(BiometryResult.SUCCESS)
            }
        }
        return (answer as? Answer.Biometry)?.result ?: BiometryResult.FAILURE
    }

    /** Whether the app is in front. */
    public val phase: DevicePhase get() = synchronized(lock) { currentPhase }

    // What the runtime changes

    /** The oldest prompt the app is waiting on: what a step prints as `prompt=`. */
    public val pendingPrompt: String? get() = synchronized(lock) { waiting.firstOrNull()?.prompt?.name }

    /** Whether a prompt for [service] is waiting. */
    public fun isPrompting(service: String): Boolean =
        synchronized(lock) { waiting.any { it.prompt == Prompt.Permission(service) } }

    /** Sets a permission, as if changed in Settings. */
    public fun setPermission(service: String, status: PermissionStatus): Unit =
        synchronized(lock) { statuses[service] = status }

    /** Why the runtime could not answer a prompt. */
    public sealed class AnswerError : Exception() {
        /** No prompt is pending. */
        public data object NoPrompt : AnswerError() {
            private fun readResolve(): Any = NoPrompt
        }

        /** The oldest prompt is of the other kind: [name] is its name. */
        public data class OtherPrompt(val name: String) : AnswerError()
    }

    /** Answers the oldest prompt, which must be a permission's: its status becomes [status]. */
    @Throws(AnswerError::class)
    public fun answerPermission(status: PermissionStatus) {
        val first = synchronized(lock) {
            val first = waiting.firstOrNull() ?: throw AnswerError.NoPrompt
            val prompt = first.prompt as? Prompt.Permission ?: throw AnswerError.OtherPrompt(first.prompt.name)
            statuses[prompt.service] = status
            waiting.removeAt(0)
        }
        first.continuation.resume(Answer.Permission(status))
    }

    /** Answers the oldest prompt, which must be the biometric one. */
    @Throws(AnswerError::class)
    public fun answerBiometry(result: BiometryResult) {
        val first = synchronized(lock) {
            val first = waiting.firstOrNull() ?: throw AnswerError.NoPrompt
            if (first.prompt != Prompt.Biometry) throw AnswerError.OtherPrompt(first.prompt.name)
            waiting.removeAt(0)
        }
        first.continuation.resume(Answer.Biometry(result))
    }

    /** Turns the network on or off. */
    public fun setOnline(online: Boolean) {
        val observers = synchronized(lock) {
            this.online = online
            onlineObservers.toList()
        }
        for (observer in observers) observer.trySend(online)
    }

    /** Sets the location, or removes it. */
    public fun setLocation(location: DeviceLocation?) {
        val observers = synchronized(lock) {
            currentLocation = location
            locationObservers.toList()
        }
        for (observer in observers) observer.trySend(location)
    }

    /** Enrolls biometry or removes it. */
    public fun setBiometryEnrolled(enrolled: Boolean): Unit = synchronized(lock) { biometryEnrolled = enrolled }

    /** Sends the app to the background or brings it back. */
    public fun setPhase(phase: DevicePhase): Unit = synchronized(lock) { currentPhase = phase }

    /** Withdraws every prompt, as quitting the app does; each request returns as if cancelled. */
    public fun withdrawAllPrompts() {
        val withdrawn = synchronized(lock) {
            waiting.toList().also { waiting.clear() }
        }
        for (item in withdrawn) {
            val answer = synchronized(lock) { withdrawnAnswer(item.prompt) }
            if (item.continuation.isActive) item.continuation.resume(answer)
        }
    }

    // Prompts

    /** Answers at once with what [immediate] returns, or else queues [prompt] and waits for the runtime to answer it. */
    private suspend fun waitForAnswer(prompt: Prompt, immediate: () -> Answer?): Answer =
        suspendCancellableCoroutine { continuation ->
            val now = synchronized(lock) {
                immediate() ?: run {
                    val entry = Waiting(prompt, continuation)
                    waiting.add(entry)
                    continuation.invokeOnCancellation { synchronized(lock) { waiting.remove(entry) } }
                    null
                }
            }
            if (now != null) continuation.resume(now)
        }

    /** What a withdrawn request returns: the status unchanged, or a failed authentication. */
    private fun withdrawnAnswer(prompt: Prompt): Answer = when (prompt) {
        is Prompt.Permission -> Answer.Permission(statuses[prompt.service] ?: PermissionStatus.NOT_DETERMINED)
        Prompt.Biometry -> Answer.Biometry(BiometryResult.FAILURE)
    }

    /** Every value [observers] are sent, starting with [current]'s, unbuffered so no change is dropped. */
    private fun <T> observe(observers: MutableList<SendChannel<T>>, current: () -> T): Flow<T> = callbackFlow {
        synchronized(lock) {
            observers.add(channel)
            trySend(current())
        }
        awaitClose { synchronized(lock) { observers.remove(channel) } }
    }.buffer(Channel.UNLIMITED)
}
