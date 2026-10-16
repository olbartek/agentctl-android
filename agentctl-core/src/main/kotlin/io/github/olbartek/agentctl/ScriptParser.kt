package io.github.olbartek.agentctl

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** One command in a script: `name` plus the raw argument text (quotes preserved). */
public data class ScriptLine(
    /** 1-based line number in the source. */
    val line: Int,
    val name: String,
    /** The rest of the command after the name, trimmed, with quotes preserved. `null` if empty. */
    val argument: String?,
) {
    /** The command as written, normalized to `name argument`. */
    val text: String get() = if (argument != null) "$name $argument" else name
}

/** A syntax error in a script. Maps to exit code 2. */
public class ScriptError(
    public val line: Int,
    public val column: Int,
    message: String,
) : Exception(message) {
    /** The error with its position, as `parse error:` reports it: `line <n>, column <n>: <message>`. */
    public val description: String get() = "line $line, column $column: $message"

    override fun equals(other: Any?): Boolean =
        other is ScriptError && other.line == line && other.column == column && other.message == message

    override fun hashCode(): Int = (line * 31 + column) * 31 + message.hashCode()

    override fun toString(): String = "ScriptError($description)"
}

/**
 * Parses the script language shared by the CLI's `run`, scenarios, `-appctl-seed` and the agent bridge
 * (CONTRACT.md §1).
 *
 * - Commands are separated by `;` or newlines. A newline is `\n` or `\r\n`; a lone `\r` is whitespace.
 * - `#` starts a comment that runs to the end of the line.
 * - Double quotes protect `;` and `#`; inside quotes `\` escapes the next character. Quotes are kept in the
 *   argument so that commands like `expect` can split on unquoted spaces; use [ArgumentText] to unquote.
 */
public object ScriptParser {
    /** The commands of [source], in order. Throws [ScriptError] on an unterminated quote. */
    @Throws(ScriptError::class)
    public fun parse(source: String): List<ScriptLine> {
        val result = mutableListOf<ScriptLine>()
        val current = mutableListOf<String>()
        var currentLine = 1
        var line = 1
        var column = 0
        var inQuotes = false
        var quoteLine = 0
        var quoteColumn = 0
        var escaping = false
        var inComment = false

        fun flush() {
            val trimmed = current.trimmingWhitespace()
            if (trimmed.isNotEmpty()) {
                val nameLength = trimmed.indexOfFirst { it.isWhitespaceGrapheme() }.let { if (it < 0) trimmed.size else it }
                val name = trimmed.subList(0, nameLength).joinToString("")
                val rest = trimmed.subList(nameLength, trimmed.size).trimmingWhitespace().joinToString("")
                result.add(ScriptLine(currentLine, name, rest.ifEmpty { null }))
            }
            current.clear()
        }

        for (character in Graphemes.of(source)) {
            column += 1
            if (character.isScriptNewline()) {
                if (inQuotes) throw ScriptError(quoteLine, quoteColumn, "unterminated quote")
                inComment = false
                flush()
                line += 1
                column = 0
                currentLine = line
                continue
            }
            if (inComment) continue
            if (inQuotes) {
                current.add(character)
                if (escaping) {
                    escaping = false
                } else if (character == "\\") {
                    escaping = true
                } else if (character == "\"") {
                    inQuotes = false
                }
                continue
            }
            when (character) {
                "\"" -> {
                    inQuotes = true
                    quoteLine = line
                    quoteColumn = column
                    current.add(character)
                }
                "#" -> inComment = true
                ";" -> {
                    flush()
                    currentLine = line
                }
                else -> {
                    if (current.trimmingWhitespace().isEmpty()) currentLine = line
                    current.add(character)
                }
            }
        }
        if (inQuotes) throw ScriptError(quoteLine, quoteColumn, "unterminated quote")
        flush()
        return result
    }

    /**
     * Parses the argument of `advance`: an unsigned integer immediately followed by one unit — `ms`, `s`, `m` or
     * `h` (`500ms`, `30s`, `5m`, `1h`; CONTRACT.md §2.2).
     *
     * Returns `null` for anything else, including a number too large to represent, whether as a `Long` or once
     * converted to seconds: `advance` reports every `null` as a usage error (exit 2), never a crash.
     */
    public fun parseDuration(text: String): AgentDuration? {
        // The multiplier to seconds; null for milliseconds. `ms` is tried before `s` and `m`, which are its
        // suffix and its first letter.
        val units = listOf<Pair<String, Long?>>("ms" to null, "s" to 1L, "m" to 60L, "h" to 3600L)
        for ((suffix, multiplier) in units) {
            if (!text.endsWith(suffix)) continue
            val number = text.dropLast(suffix.length)
            if (number.isEmpty() || !number.all { it in '0'..'9' }) return null
            val value = number.toLongOrNull() ?: return null
            if (multiplier == null) return AgentDuration.ofMilliseconds(value)
            val seconds = try {
                Math.multiplyExact(value, multiplier)
            } catch (_: ArithmeticException) {
                return null
            }
            return AgentDuration.ofSeconds(seconds)
        }
        return null
    }
}

/**
 * A non-negative span of virtual time, exact to the millisecond, as large as `Long.MAX_VALUE` seconds: the range
 * of the reference's `Duration` that `advance` can reach. [kotlin.time.Duration] saturates to infinity long
 * before that, so `advance` counts in this instead (CONTRACT.md §2.2).
 */
public class AgentDuration private constructor(
    /** The duration in milliseconds, exactly. */
    public val totalMilliseconds: java.math.BigInteger,
) : Comparable<AgentDuration> {
    /** This plus [other]. */
    public operator fun plus(other: AgentDuration): AgentDuration = AgentDuration(totalMilliseconds + other.totalMilliseconds)

    /** This less [other]. */
    public operator fun minus(other: AgentDuration): AgentDuration = AgentDuration(totalMilliseconds - other.totalMilliseconds)

    override fun compareTo(other: AgentDuration): Int = totalMilliseconds.compareTo(other.totalMilliseconds)

    /** The milliseconds, saturated at `Long.MAX_VALUE`: what a virtual clock counting in `Long` can move. */
    public val saturatedMilliseconds: Long
        get() = if (totalMilliseconds > java.math.BigInteger.valueOf(Long.MAX_VALUE)) Long.MAX_VALUE else totalMilliseconds.toLong()

    /** As a [Duration], which saturates to [Duration.INFINITE] beyond its own range. */
    public fun toDuration(): Duration = saturatedMilliseconds.milliseconds

    override fun equals(other: Any?): Boolean = other is AgentDuration && other.totalMilliseconds == totalMilliseconds

    override fun hashCode(): Int = totalMilliseconds.hashCode()

    override fun toString(): String = "${totalMilliseconds}ms"

    public companion object {
        /** No time. */
        public val ZERO: AgentDuration = AgentDuration(java.math.BigInteger.ZERO)

        /** [milliseconds] milliseconds. */
        public fun ofMilliseconds(milliseconds: Long): AgentDuration = AgentDuration(java.math.BigInteger.valueOf(milliseconds))

        /** [seconds] seconds, exactly, even past the range of [Duration]. */
        public fun ofSeconds(seconds: Long): AgentDuration =
            AgentDuration(java.math.BigInteger.valueOf(seconds) * java.math.BigInteger.valueOf(1000))

        /** [duration] in whole milliseconds; any fraction of a millisecond is dropped. */
        public fun of(duration: Duration): AgentDuration = ofMilliseconds(duration.inWholeMilliseconds)
    }
}

/** Helpers for argument text that may contain double-quoted parts. */
public object ArgumentText {
    /**
     * Removes surrounding quotes and resolves escapes when the whole argument is one quoted string.
     * `"a;b"` → `a;b`, `Alice Smith` → `Alice Smith`. Only one quoted span counts: the quote that closes the opening
     * one must be the last character, so `"a"b"c"` and `"a" "b"` stay as written (CONTRACT.md §1.2).
     */
    public fun unquoted(text: String): String {
        val characters = Graphemes.of(text)
        if (characters.firstOrNull() != "\"") return text
        val content = StringBuilder()
        var escaping = false
        for (index in 1 until characters.size) {
            val character = characters[index]
            if (escaping) {
                content.append(character)
                escaping = false
            } else if (character == "\\") {
                escaping = true
            } else if (character == "\"") {
                // The closing quote: the argument is one quoted span only if nothing follows it.
                return if (index == characters.lastIndex) content.toString() else text
            } else {
                content.append(character)
            }
        }
        return text
    }

    /**
     * Splits on unquoted whitespace, then removes quotes and resolves escapes in each token.
     * `name="Alice Smith" email=a` → `["name=Alice Smith", "email=a"]`.
     */
    public fun tokens(text: String): List<String> = splitTokens(text).map { it.text }

    /**
     * Splits [text] into `key=value` pairs at each token's first *unquoted* `=`, the key and value unquoted
     * (CONTRACT.md §2.1). `null` for a token whose `=` is missing, only inside quotes, or first: `"title=a b"` is not
     * a pair, `"a=b"=c` is the key `a=b`.
     */
    internal fun pairs(text: String): List<kotlin.Pair<String, kotlin.Pair<String, String>?>> = splitTokens(text).map { token ->
        val equals = token.equals
        if (equals == null || equals <= 0) token.text to null
        else token.text to (token.text.substring(0, equals) to token.text.substring(equals + 1))
    }

    /** A token, unquoted, with the offset in it of its first `=` that was outside quotes. */
    private class Token(val text: String, val equals: Int?)

    private fun splitTokens(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val current = StringBuilder()
        var equals: Int? = null
        var hasToken = false
        var inQuotes = false
        var escaping = false
        for (character in Graphemes.of(text)) {
            if (inQuotes) {
                if (escaping) {
                    current.append(character)
                    escaping = false
                } else if (character == "\\") {
                    escaping = true
                } else if (character == "\"") {
                    inQuotes = false
                } else {
                    current.append(character)
                }
            } else if (character == "\"") {
                inQuotes = true
                hasToken = true
            } else if (character.isWhitespaceGrapheme()) {
                if (hasToken) tokens.add(Token(current.toString(), equals))
                current.setLength(0)
                equals = null
                hasToken = false
            } else {
                // A grapheme, not a `Char`: `=` with a combining mark on it is not an `=`, as in the reference.
                if (character == "=" && equals == null) equals = current.length
                current.append(character)
                hasToken = true
            }
        }
        if (hasToken) tokens.add(Token(current.toString(), equals))
        return tokens
    }
}
