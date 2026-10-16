package io.github.olbartek.agentctl

/**
 * This text as an `Int` when it is what Swift's `Int(_:)` reads: an optional `+` or `-`, then the ASCII digits `0`–`9`
 * and nothing else (no spaces, no `_`, no `0x`); `null` for anything else or a value out of range. Kotlin's
 * `toIntOrNull` also reads any Unicode digit (`٢`, `９`), so a command argument or launch value parsed with it would
 * pass on Android where the reference refuses it; parse such text with this instead:
 *
 * ```kotlin
 * val id = text.toAsciiIntOrNull() ?: invalidArgument("expected an item id such as 2")
 * ```
 */
public fun String.toAsciiIntOrNull(): Int? {
    val digits = if (startsWith('+') || startsWith('-')) substring(1) else this
    if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
    return toIntOrNull()
}
