package io.github.olbartek.agentctl.examples.agentshop.models

import java.text.BreakIterator
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Text as the Swift reference counts and classifies it: grapheme clusters (Swift's `Character`), not UTF-16 `Char`s,
 * so a validation rule such as "at least 8 characters" or "6 digits" gives the same answer in both ports.
 */
object Graphemes {
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

/** Swift's `Character.isNumber`: the first scalar is a digit, a letter-like number or another number (½, ⅚). */
fun String.isNumberCharacter(): Boolean {
    if (isEmpty()) return false
    return when (Character.getType(codePointAt(0)).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }
}

/** Swift's `Character.isLetter`: the first scalar is alphabetic. */
fun String.isLetterCharacter(): Boolean = isNotEmpty() && Character.isAlphabetic(codePointAt(0))

/** Swift's `Character.isWhitespace`: the first scalar has the Unicode `White_Space` property. */
fun String.isWhitespaceCharacter(): Boolean {
    if (isEmpty()) return false
    return when (codePointAt(0)) {
        in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }
}

/** Swift's `Character.isASCII`. */
fun String.isAsciiCharacter(): Boolean = all { it.code < 0x80 }

/** Swift's `trimmingCharacters(in: .whitespaces)`: spaces (`Zs`) and tabs, not newlines. */
fun String.trimmingWhitespaces(): String = trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

/** The first `count` graphemes that are numbers: what a code field keeps of what is typed into it. */
fun String.digitsPrefix(count: Int): String = Graphemes.of(this).filter { it.isNumberCharacter() }.take(count).joinToString("")

/**
 * Runs a client call and returns its [Outcome], turning any error into the client's own with [error]. Cancellation
 * is not an error: it propagates, so a cancelled effect ends instead of reporting a failure.
 */
suspend fun <T, E> attempt(error: (Throwable) -> E, call: suspend () -> T): Outcome<T, E> = try {
    Outcome.Success(call())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    Outcome.Failure(error(failure))
}
