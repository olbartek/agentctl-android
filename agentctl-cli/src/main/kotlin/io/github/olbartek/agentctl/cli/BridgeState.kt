package io.github.olbartek.agentctl.cli

import io.github.olbartek.agentctl.BridgeDefaults
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * The launch state: which app `app launch` last started, on which device, and the port its bridge answered on.
 * It lives at `<outputPath>/bridge.json`, and `app run`, `app state` and `app screens` read their port from it, so
 * two apps (or two devices) on one Mac never have to be given ports by hand.
 *
 * The file is written as agentctl-ios writes it (Foundation's `.prettyPrinted, .sortedKeys,
 * .withoutEscapingSlashes`, and one trailing newline), so either port's CLI can read the other's.
 */
internal data class BridgeState(
    /** `android` here, `ios` in agentctl-ios. */
    val platform: String,
    /** The `adb` serial. */
    val device: String,
    val port: Int,
    /** The application id. */
    val appId: String,
    val launchedAt: Instant,
) {
    fun render(): String {
        val fields = sortedMapOf(
            "appId" to FlatJson.quote(appId),
            "device" to FlatJson.quote(device),
            "launchedAt" to FlatJson.quote(launchedAt.truncatedTo(ChronoUnit.SECONDS).toString()),
            "platform" to FlatJson.quote(platform),
            "port" to port.toString(),
        )
        return FlatJson.pretty(fields)
    }

    fun save(layout: Layout) {
        val file = file(layout)
        file.parentFile?.mkdirs()
        // Whole or not at all: a reader never sees half a file.
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(render())
        if (!temporary.renameTo(file)) {
            file.writeText(render())
            temporary.delete()
        }
    }

    companion object {
        const val PLATFORM: String = "android"

        fun file(layout: Layout): File = File(layout.output, "bridge.json")

        /** The saved state, `null` when there is none, or [Unreadable] when there is a file that is not one. */
        @Throws(Unreadable::class)
        fun load(layout: Layout): BridgeState? {
            val file = file(layout)
            if (!file.exists()) return null
            val text = try {
                file.readText()
            } catch (error: IOException) {
                throw Unreadable(error.toString())
            }
            return parse(text) ?: throw Unreadable("not a launch state (expected appId, device, launchedAt, platform and port)")
        }

        /** A flat JSON object with these five fields, in any order and layout; anything else is `null`. */
        fun parse(text: String): BridgeState? {
            val fields = FlatJson.parse(text) ?: return null
            val port = (fields["port"] as? Long)?.takeIf { it in 1..65535 }?.toInt() ?: return null
            val launchedAt = (fields["launchedAt"] as? String)?.let {
                try {
                    Instant.parse(it)
                } catch (_: DateTimeParseException) {
                    null
                }
            } ?: return null
            return BridgeState(
                platform = fields["platform"] as? String ?: return null,
                device = fields["device"] as? String ?: return null,
                port = port,
                appId = fields["appId"] as? String ?: return null,
                launchedAt = launchedAt,
            )
        }

    }
}

/** `bridge.json` exists but cannot be read, or is not a launch state. */
internal class Unreadable(val reason: String) : Exception(reason)

/** Which port an `app` command talks to, and where that choice came from. */
internal object Ports {
    const val ENVIRONMENT_VARIABLE: String = "APPCTL_PORT"

    /** The ports `app launch` tries, in order, when it is not given one. */
    val SCAN: IntRange = BridgeDefaults.PORT..(BridgeDefaults.PORT + 99)

    enum class Source { FLAG, ENVIRONMENT, STATE, DEFAULT }

    data class Resolved(val port: Int, val source: Source, val state: BridgeState? = null)

    /** `--port` is not a port: a usage error (exit 2). 0 would let the app pick a port the CLI never learns. */
    class BadFlagPort(val value: Int) : Exception("--port must be a port from 1 to 65535, not $value")

    /** [flag], if it is a port; throws [BadFlagPort] if it is not. */
    fun validated(flag: Int?): Int? = flag?.also { if (it !in 1..65535) throw BadFlagPort(it) }

    /** `APPCTL_PORT` is set but is not a port: a usage error (exit 2). */
    class BadEnvironmentPort(val value: String) : Exception("$ENVIRONMENT_VARIABLE is not a port: '$value' (expected 1-65535)")

    /**
     * For `app run`, `app state` and `app screens`: `--port`, else `APPCTL_PORT`, else the launch state, else 8765.
     * [state] is only read when neither of the first two is given.
     */
    fun client(flag: Int?, environment: Map<String, String>, state: () -> BridgeState?): Resolved {
        validated(flag)?.let { return Resolved(it, Source.FLAG) }
        environmentPort(environment)?.let { return Resolved(it, Source.ENVIRONMENT) }
        state()?.let { return Resolved(it.port, Source.STATE, it) }
        return Resolved(BridgeDefaults.PORT, Source.DEFAULT)
    }

    /** For a launch: `--port`, else `APPCTL_PORT`, used exactly; `null` means "find a free one". */
    fun explicit(flag: Int?, environment: Map<String, String>): Int? = validated(flag) ?: environmentPort(environment)

    /** The first port of [SCAN] above [after] (if given) that [isFree] accepts, or `null` when none is. */
    fun firstFree(after: Int? = null, isFree: (Int) -> Boolean): Int? = SCAN.firstOrNull { (after == null || it > after) && isFree(it) }

    /** The local ports in state LISTEN (`0A`) in `/proc/net/tcp` and `/proc/net/tcp6` output. */
    fun listening(procNetTcp: String): Set<Int> = procNetTcp.lines().mapNotNull { line ->
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size < 4 || columns[3] != "0A") return@mapNotNull null
        columns[1].substringAfterLast(':').toIntOrNull(16)
    }.toSet()

    /** One line of `adb forward --list`: `<serial> tcp:<host> tcp:<device>` (other kinds are left out). */
    data class Forward(val serial: String, val host: Int, val device: Int)

    fun forwards(forwardList: String): List<Forward> = forwardList.lines().mapNotNull { line ->
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size < 3 || !columns[1].startsWith("tcp:") || !columns[2].startsWith("tcp:")) return@mapNotNull null
        val host = columns[1].removePrefix("tcp:").toIntOrNull() ?: return@mapNotNull null
        val device = columns[2].removePrefix("tcp:").toIntOrNull() ?: return@mapNotNull null
        Forward(columns[0], host, device)
    }

    /**
     * The host port of the forward this app's last launch on [serial] left behind, if nothing on the device listens
     * behind it any more: the one `bridge.json` records for this app and device. Another app's forward, or another
     * tool's, is never this.
     */
    fun ownStaleForward(recorded: BridgeState?, serial: String, appId: String, forwardList: String, listening: Set<Int>): Int? {
        if (recorded == null || recorded.platform != BridgeState.PLATFORM || recorded.device != serial || recorded.appId != appId) return null
        val forward = forwards(forwardList).firstOrNull { it.serial == serial && it.host == recorded.port } ?: return null
        return forward.host.takeIf { forward.device !in listening }
    }

    private fun environmentPort(environment: Map<String, String>): Int? {
        val value = environment[ENVIRONMENT_VARIABLE]?.takeIf { it.isNotEmpty() } ?: return null
        return value.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw BadEnvironmentPort(value)
    }
}

/**
 * Just enough JSON to read `bridge.json`: one object whose values are strings, integers, booleans or null.
 * Anything else, including nesting, is not a launch state.
 */
internal object FlatJson {
    /** [text] as a JSON string, escaped as Foundation's encoder escapes it (slashes left alone). */
    fun quote(text: String): String = buildString {
        append('"')
        for (character in text) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    /**
     * Fields as Foundation's `.prettyPrinted, .sortedKeys` encoder lays them out (`"key" : value`, two-space
     * indent), with one trailing newline: the form of `bridge.json` and `record.json`. Values are already JSON.
     */
    fun pretty(fields: Map<String, String>): String =
        fields.toSortedMap().entries.joinToString(",\n", prefix = "{\n", postfix = "\n}\n") { (key, value) -> "  \"$key\" : $value" }

    /** The same, compact: `{"key":value,...}`, as `.sortedKeys` without `.prettyPrinted` writes it. */
    fun compact(fields: Map<String, String>): String =
        fields.toSortedMap().entries.joinToString(",", prefix = "{", postfix = "}") { (key, value) -> "\"$key\":$value" }

    fun parse(text: String): Map<String, Any?>? = try {
        Reader(text).readObject()
    } catch (_: IllegalArgumentException) {
        null
    }

    private class Reader(private val text: String) {
        private var index = 0

        fun readObject(): Map<String, Any?> {
            val fields = mutableMapOf<String, Any?>()
            expect('{')
            skipSpace()
            if (peek() == '}') {
                index++
                return end(fields)
            }
            while (true) {
                skipSpace()
                val key = readString()
                skipSpace()
                expect(':')
                skipSpace()
                fields[key] = readValue()
                skipSpace()
                when (next()) {
                    ',' -> continue
                    '}' -> return end(fields)
                    else -> fail()
                }
            }
        }

        private fun end(fields: Map<String, Any?>): Map<String, Any?> {
            skipSpace()
            require(index == text.length)
            return fields
        }

        private fun readValue(): Any? = when (peek()) {
            '"' -> readString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> readInteger()
        }

        private fun readInteger(): Long {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            return text.substring(start, index).toLongOrNull() ?: fail()
        }

        private fun literal(word: String, value: Any?): Any? {
            require(text.startsWith(word, index))
            index += word.length
            return value
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                when (val character = next()) {
                    '"' -> return out.toString()
                    '\\' -> when (val escaped = next()) {
                        '"', '\\', '/' -> out.append(escaped)
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            require(index + 4 <= text.length)
                            out.append(text.substring(index, index + 4).toIntOrNull(16)?.toChar() ?: fail())
                            index += 4
                        }
                        else -> fail()
                    }
                    else -> out.append(character)
                }
            }
        }

        private fun skipSpace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun peek(): Char = text.getOrNull(index) ?: fail()

        private fun next(): Char = text.getOrNull(index++) ?: fail()

        private fun expect(character: Char) = require(next() == character)

        private fun fail(): Nothing = throw IllegalArgumentException("not a launch state")
    }
}
