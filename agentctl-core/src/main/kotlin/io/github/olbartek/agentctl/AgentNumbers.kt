package io.github.olbartek.agentctl

/** Numbers read from text as the reference reads them. */
public object AgentNumbers {
    /**
     * [text] as an `Int` when it is what Swift's `Int(_:)` reads: an optional `+` or `-`, then the ASCII digits `0`–`9`
     * and nothing else (no spaces, no `_`, no `0x`); `null` for anything else or a value out of range. The range is
     * Kotlin's 32-bit `Int`, where Swift's `Int` is 64-bit: `2147483648` is `null` here and a number there. Kotlin's
     * `toIntOrNull` also reads any Unicode digit (`٢`, `９`), so a command argument or launch value parsed with it
     * would pass on Android where the reference refuses it; parse such text with this instead:
     *
     * ```kotlin
     * val id = AgentNumbers.int(text) ?: invalidArgument("expected an item id such as 2")
     * ```
     */
    public fun int(text: String): Int? {
        val digits = if (text.startsWith('+') || text.startsWith('-')) text.substring(1) else text
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        return text.toIntOrNull()
    }
}
