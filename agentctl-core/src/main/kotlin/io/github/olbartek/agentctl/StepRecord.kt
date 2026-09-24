package io.github.olbartek.agentctl

/** The outcome of one script command, as printed by the CLI's `run` and returned by the agent bridge. */
public data class StepRecord(
    val command: String,
    val screen: String,
    val summary: List<SummaryItem>,
    val calls: List<String>,
    val error: String?,
    val pending: Int,
    /** `false` when settling hit its real-time limit. */
    val settled: Boolean = true,
    val ok: Boolean = true,
    /** Why the step failed, or extra detail. */
    val message: String? = null,
    /** A state diff, for `--diff`. */
    val diff: String? = null,
) {
    val snapshot: StepSnapshot get() = StepSnapshot(screen, summary, calls, error, pending)
}

/** Renders steps in the text format of CONTRACT.md §3, and in the JSON form of its §8.4. */
public object StepFormatter {
    /**
     * ```text
     * > submit
     *   screen=<path> <key>=<value> … calls=<client.method>,…
     * ```
     */
    public fun text(step: StepRecord): String {
        val fields = mutableListOf("screen=${step.screen}")
        step.summary.mapTo(fields) { "${it.key}=${quoted(it.value)}" }
        if (step.calls.isNotEmpty()) fields.add("calls=${step.calls.joinToString(",")}")
        if (step.error != null) fields.add("error=${step.error}")
        if (step.pending != 0) fields.add("pending=${step.pending}")
        if (!step.settled) fields.add("settled=false")
        val lines = mutableListOf("> ${step.command}", "  " + fields.joinToString(" "))
        step.message?.let { message ->
            message.split("\n").mapTo(lines) { "  ${if (step.ok) "" else "FAIL "}$it" }
        }
        val diff = step.diff
        if (!diff.isNullOrEmpty()) diff.split("\n").mapTo(lines) { "    $it" }
        return lines.joinToString("\n")
    }

    public fun text(steps: List<StepRecord>): String = steps.joinToString("\n") { text(it) }

    /** A JSON array of `{calls, command, error, message?, ok, pending, screen, settled, summary}`, keys sorted. */
    public fun json(steps: List<StepRecord>): String = Json.render(Json.Array(steps.map(::jsonStep)))

    /**
     * The JSON form of a whole run: [json]'s array — unless the run failed with a message that belongs to no step
     * (a script that did not parse), which the array has no place for. Then it is an object that carries the
     * message beside the steps: `{"error": "parse error: …", "steps": []}`.
     */
    public fun json(steps: List<StepRecord>, error: String?): String {
        if (error == null) return json(steps)
        return Json.render(Json.Object(mapOf("error" to Json.Str(error), "steps" to Json.Array(steps.map(::jsonStep)))))
    }

    private fun jsonStep(step: StepRecord): Json.Value {
        val fields = linkedMapOf<String, Json.Value>(
            "command" to Json.Str(step.command),
            "screen" to Json.Str(step.screen),
            // A later duplicate key wins, as in the reference's `uniquingKeysWith: { $1 }`.
            "summary" to Json.Object(step.summary.associate { it.key to Json.Str(it.value) }),
            "calls" to Json.Array(step.calls.map(Json::Str)),
            "error" to (step.error?.let(Json::Str) ?: Json.Null),
            "pending" to Json.Num(step.pending.toLong()),
            "settled" to Json.Bool(step.settled),
            "ok" to Json.Bool(step.ok),
        )
        step.message?.let { fields["message"] = Json.Str(it) }
        return Json.Object(fields)
    }

    private fun quoted(value: String): String =
        if (value.isEmpty() || containsWhitespace(value)) "\"$value\"" else value
}

/**
 * The little JSON the step format needs, written the way the reference's `JSONEncoder` writes it with
 * `[.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]`: two-space indents, `"key" : value`, and an empty
 * array or object as an opening bracket, a blank line and the closing bracket. The JSON forms are specified by
 * their keys and values only (CONTRACT.md §7); matching the layout too keeps fixtures interchangeable.
 */
internal object Json {
    sealed interface Value
    data class Str(val value: String) : Value
    data class Num(val value: Long) : Value
    data class Bool(val value: Boolean) : Value
    data object Null : Value
    data class Array(val items: List<Value>) : Value
    data class Object(val fields: Map<String, Value>) : Value

    fun render(value: Value): String = StringBuilder().also { write(value, it, 0) }.toString()

    private fun write(value: Value, out: StringBuilder, indent: Int) {
        when (value) {
            is Str -> writeString(value.value, out)
            is Num -> out.append(value.value)
            is Bool -> out.append(value.value)
            Null -> out.append("null")
            is Array -> {
                out.append("[\n")
                if (value.items.isEmpty()) out.append("\n")
                value.items.forEachIndexed { index, item ->
                    out.append(" ".repeat(indent + 2))
                    write(item, out, indent + 2)
                    out.append(if (index < value.items.size - 1) ",\n" else "\n")
                }
                out.append(" ".repeat(indent)).append("]")
            }
            is Object -> {
                out.append("{\n")
                if (value.fields.isEmpty()) out.append("\n")
                val keys = value.fields.keys.sorted()
                keys.forEachIndexed { index, key ->
                    out.append(" ".repeat(indent + 2))
                    writeString(key, out)
                    out.append(" : ")
                    write(value.fields.getValue(key), out, indent + 2)
                    out.append(if (index < keys.size - 1) ",\n" else "\n")
                }
                out.append(" ".repeat(indent)).append("}")
            }
        }
    }

    private fun writeString(text: String, out: StringBuilder) {
        out.append('"')
        for (character in text) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (character < ' ') out.append("\\u%04x".format(character.code)) else out.append(character)
            }
        }
        out.append('"')
    }
}
