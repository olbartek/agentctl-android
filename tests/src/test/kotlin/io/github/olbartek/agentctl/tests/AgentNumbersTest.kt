package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.AgentNumbers
import kotlin.test.Test
import kotlin.test.assertEquals

/** `AgentNumbers.int` reads what Swift's `Int(_:)` reads (each value printed by Swift 6.3.3), within Kotlin's `Int`. */
class AgentNumbersTest {
    @Test
    fun onlyASignAndAsciiDigits() {
        val cases = mapOf(
            "5" to 5, "+5" to 5, "-5" to -5, "007" to 7, "-0" to 0, "2147483647" to Int.MAX_VALUE, "-2147483648" to Int.MIN_VALUE,
            "٢" to null, "９" to null, "²" to null, "1٢" to null, "−5" to null,
            "" to null, "+" to null, "-" to null, "+-5" to null, " 5" to null, "5 " to null, "5\u000B" to null,
            "0x10" to null, "1_000" to null, "2147483648" to null,
        )
        for ((text, value) in cases) assertEquals(value, AgentNumbers.int(text), text)
    }
}
