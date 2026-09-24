package io.github.olbartek.agentctl

/** What an `expect` step is checked against: the state after the previous step. */
public data class StepSnapshot(
    val screen: String,
    val summary: List<SummaryItem>,
    /** Mock calls made during the previous step. */
    val calls: List<String>,
    val error: String?,
    val pending: Int,
)

/**
 * `expect k=v [k=v …]` (CONTRACT.md §4).
 *
 * Keys: `screen`, any summary key, `call` (repeatable; the method was called during the previous step),
 * `error` (`none` = no error) and `pending` (sleeps waiting on the clock, such as a countdown — see
 * [CountingClock.activeSleeps]).
 */
public data class Expectation(val pairs: List<Pair>) {
    public data class Pair(val key: String, val value: String)

    /** Returns one failure message per unmet pair; empty when everything matches. */
    public fun evaluate(snapshot: StepSnapshot): List<String> = pairs.mapNotNull { pair ->
        when (pair.key) {
            "screen" -> if (snapshot.screen == pair.value) null else mismatch(pair, snapshot.screen)
            "call" -> if (pair.value in snapshot.calls) {
                null
            } else {
                val calls = if (snapshot.calls.isEmpty()) "none" else snapshot.calls.joinToString(",")
                "expected call=${pair.value}, got calls=$calls"
            }
            "error" -> {
                val actual = snapshot.error ?: "none"
                if (actual == pair.value) null else mismatch(pair, actual)
            }
            "pending" -> {
                val actual = snapshot.pending.toString()
                if (actual == pair.value) null else mismatch(pair, actual)
            }
            else -> {
                val item = snapshot.summary.firstOrNull { it.key == pair.key }
                if (item == null) {
                    val available = (RESERVED_KEYS + snapshot.summary.map { it.key }).joinToString(", ")
                    "unknown key '${pair.key}' on ${snapshot.screen}; available: $available"
                } else if (item.value == pair.value) {
                    null
                } else {
                    mismatch(pair, item.value)
                }
            }
        }
    }

    private fun mismatch(pair: Pair, actual: String): String = "expected ${pair.key}=${pair.value}, got ${pair.key}=$actual"

    public companion object {
        public val RESERVED_KEYS: List<String> = listOf("screen", "call", "error", "pending")

        /** Parses the argument of `expect`. Values containing spaces must be quoted: `name="Alice Smith"`. */
        @Throws(ExpectationSyntaxError::class)
        public fun parse(argument: String?): Expectation {
            if (argument == null) throw ExpectationSyntaxError("expect needs at least one key=value pair")
            val pairs = ArgumentText.tokens(argument).map { token ->
                // A key is at least one character: the `=` must not be the token's first.
                val equals = token.indexOf('=')
                if (equals <= 0) throw ExpectationSyntaxError("expected key=value, got '$token'")
                Pair(token.substring(0, equals), token.substring(equals + 1))
            }
            return Expectation(pairs)
        }
    }
}

public class ExpectationSyntaxError(message: String) : Exception(message)
