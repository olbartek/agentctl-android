package io.github.olbartek.agentctl

import java.text.BreakIterator
import java.util.Locale

/**
 * Text as the Swift reference sees it: a sequence of grapheme clusters (Swift's `Character`), not of UTF-16
 * `Char`s. The script parser, the argument tokenizer and the column widths of `screens` all count and compare
 * graphemes, so a port that counted `Char`s would report other columns for the same script (CONTRACT.md §1.4).
 */
internal object Graphemes {
    fun of(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val result = ArrayList<String>(text.length)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            result.add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
        return result
    }

    fun count(text: String): Int = of(text).size
}

/**
 * Swift's `Character.isWhitespace`: the first scalar has the Unicode `White_Space` property. That is wider than
 * `Char.isWhitespace` in one place (U+0085, NEXT LINE) and narrower in none, so it is spelled out.
 */
internal fun String.isWhitespaceGrapheme(): Boolean {
    if (isEmpty()) return false
    return codePointAt(0).isUnicodeWhiteSpace()
}

internal fun Int.isUnicodeWhiteSpace(): Boolean = when (this) {
    in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
    else -> false
}

/** `true` when `text` contains a whitespace grapheme, the rule of CONTRACT.md §3.2. */
internal fun containsWhitespace(text: String): Boolean = Graphemes.of(text).any { it.isWhitespaceGrapheme() }

/** Trims whitespace graphemes at both ends, as the reference's `trimmingWhitespace()`. */
internal fun List<String>.trimmingWhitespace(): List<String> {
    val start = indexOfFirst { !it.isWhitespaceGrapheme() }
    if (start < 0) return emptyList()
    val end = indexOfLast { !it.isWhitespaceGrapheme() }
    return subList(start, end + 1)
}

/** Pads with spaces to `width` graphemes; never truncates. */
internal fun String.padGraphemes(width: Int): String {
    val count = Graphemes.count(this)
    return if (count >= width) this else this + " ".repeat(width - count)
}
