package io.github.olbartek.agentctl

/** What an `expect` step is checked against: the state after the previous step. */
public data class StepSnapshot(
    val screen: String,
    val summary: List<SummaryItem>,
    /** Mock calls made during the previous step. */
    val calls: List<String>,
    val error: String?,
    val pending: Int,
    /** The system prompt the app is waiting on, if any. */
    val prompt: String? = null,
)

/**
 * `expect k=v [k=v …]` (CONTRACT.md §4).
 *
 * Keys: `screen`, any summary key, `call` (repeatable; the method was called during the previous step),
 * `error` (`none` = no error), `pending` (sleeps waiting on the clock, such as a countdown — see
 * [CountingClock.activeSleeps]) and `prompt` (the system prompt the app is waiting on; `none` = no prompt).
 */
public data class Expectation(val pairs: List<Pair>) {
    /** One `key=value` of an `expect`. */
    public data class Pair(val key: String, val value: String)

    /** Returns one failure message per unmet pair; empty when everything matches. */
    public fun evaluate(snapshot: StepSnapshot): List<String> = pairs.mapNotNull { pair ->
        when (AgentText.canonical(pair.key)) {
            "screen" -> if (AgentText.equivalent(snapshot.screen, pair.value)) null else mismatch(pair, snapshot.screen)
            "call" -> if (snapshot.calls.any { AgentText.equivalent(it, pair.value) }) {
                null
            } else {
                val calls = if (snapshot.calls.isEmpty()) "none" else snapshot.calls.joinToString(",")
                "expected call=${pair.value}, got calls=$calls"
            }
            "error" -> {
                val actual = snapshot.error ?: "none"
                if (AgentText.equivalent(actual, pair.value)) null else mismatch(pair, actual)
            }
            "pending" -> {
                val actual = snapshot.pending.toString()
                if (AgentText.equivalent(actual, pair.value)) null else mismatch(pair, actual)
            }
            "prompt" -> {
                val actual = snapshot.prompt ?: "none"
                if (AgentText.equivalent(actual, pair.value)) null else mismatch(pair, actual)
            }
            else -> {
                val item = snapshot.summary.firstOrNull { AgentText.equivalent(it.key, pair.key) }
                if (item == null) {
                    val available = (RESERVED_KEYS + snapshot.summary.map { it.key }).joinToString(", ")
                    "unknown key '${pair.key}' on ${snapshot.screen}; available: $available"
                } else if (AgentText.equivalent(item.value, pair.value)) {
                    null
                } else {
                    mismatch(pair, item.value)
                }
            }
        }
    }

    private fun mismatch(pair: Pair, actual: String): String = "expected ${pair.key}=${pair.value}, got ${pair.key}=$actual"

    public companion object {
        /** The keys that are not summary keys, listed first when an `expect` names a key the screen does not have. */
        public val RESERVED_KEYS: List<String> = listOf("screen", "call", "error", "pending", "prompt")

        /**
         * Parses the argument of `expect`. Values containing spaces must be quoted: `name="Alice Smith"`. Each token's
         * first unquoted `=` splits it; a token whose `=` is only inside quotes, or first, is a syntax error.
         */
        @Throws(ExpectationSyntaxError::class)
        public fun parse(argument: String?): Expectation {
            if (argument == null) throw ExpectationSyntaxError("expect needs at least one key=value pair")
            val pairs = ArgumentText.pairs(argument).map { (token, pair) ->
                // The `=` must be outside quotes: `"title=Second item"` is one quoted value, not a pair (CONTRACT.md §2.1).
                pair ?: throw ExpectationSyntaxError("expected key=value, got '$token'")
                Pair(pair.first, pair.second)
            }
            return Expectation(pairs)
        }
    }
}

/** An `expect` argument that is not a list of `key=value` pairs. Maps to exit code 2. */
public class ExpectationSyntaxError(message: String) : Exception(message)
