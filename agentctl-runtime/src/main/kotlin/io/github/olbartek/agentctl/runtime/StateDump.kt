package io.github.olbartek.agentctl.runtime

/**
 * The root state as `state` and `GET /state` print it, and the per-step diff of `run --diff`: the counterpart of
 * the reference's `customDump`. It indents a Kotlin `toString()` — which for data classes, lists and maps is
 * `Name(a=1, b=[x, y])` — one field per line. Informational only (CONTRACT.md §8.4): nothing parses it.
 */
public object StateDump {
    public fun render(value: Any?): String {
        val text = value.toString()
        val out = StringBuilder()
        var depth = 0
        var index = 0
        fun newline() {
            out.append('\n').append("  ".repeat(depth))
        }
        while (index < text.length) {
            val character = text[index]
            when {
                character in "([{" -> {
                    val close = matching(character)
                    if (index + 1 < text.length && text[index + 1] == close) {
                        out.append(character).append(close)
                        index += 1
                    } else {
                        out.append(character)
                        depth += 1
                        newline()
                    }
                }
                character in ")]}" -> {
                    depth = maxOf(0, depth - 1)
                    newline()
                    out.append(character)
                }
                character == ',' && index + 1 < text.length && text[index + 1] == ' ' && depth > 0 -> {
                    out.append(',')
                    newline()
                    index += 1
                }
                else -> out.append(character)
            }
            index += 1
        }
        return out.toString()
    }

    /**
     * The lines of [render] that changed from `before` to `after`, as `- old` / `+ new`, with unchanged lines as
     * context and long unchanged runs elided. Empty when nothing changed.
     */
    public fun diff(before: Any?, after: Any?): String {
        val old = render(before).split("\n")
        val new = render(after).split("\n")
        if (old == new) return ""
        // Longest common subsequence of lines: small states, so the quadratic table is fine.
        val lengths = Array(old.size + 1) { IntArray(new.size + 1) }
        for (i in old.indices.reversed()) {
            for (j in new.indices.reversed()) {
                lengths[i][j] = if (old[i] == new[j]) lengths[i + 1][j + 1] + 1 else maxOf(lengths[i + 1][j], lengths[i][j + 1])
            }
        }
        val lines = mutableListOf<Pair<Char, String>>()
        var i = 0
        var j = 0
        while (i < old.size || j < new.size) {
            when {
                i < old.size && j < new.size && old[i] == new[j] -> lines.add(' ' to old[i++]).also { j++ }
                i < old.size && (j == new.size || lengths[i + 1][j] >= lengths[i][j + 1]) -> lines.add('-' to old[i++])
                else -> lines.add('+' to new[j++])
            }
        }
        val changed = lines.indices.filter { lines[it].first != ' ' }
        val keep = changed.flatMap { (it - 2)..(it + 2) }.toSet()
        val out = mutableListOf<String>()
        var elided = false
        lines.forEachIndexed { index, (mark, line) ->
            if (index in keep) {
                out.add("$mark $line")
                elided = false
            } else if (!elided) {
                out.add("  …")
                elided = true
            }
        }
        return out.joinToString("\n")
    }

    private fun matching(open: Char): Char = when (open) {
        '(' -> ')'
        '[' -> ']'
        else -> '}'
    }
}
