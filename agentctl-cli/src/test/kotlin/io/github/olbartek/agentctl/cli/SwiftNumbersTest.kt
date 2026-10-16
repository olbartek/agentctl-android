package io.github.olbartek.agentctl.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What this guards: the CLI reads and prints numbers as agentctl-ios's Swift does, so a message quoting a value is the
 * same in both ports. Every expected text here was printed by `swift` 6 on macOS (`"\(x)"`).
 */
class SwiftNumbersTest {
    @Test
    fun aDoubleIsPrintedAsSwiftInterpolatesIt() {
        val cases = listOf<Pair<Double, String>>(
            1e7 to "10000000.0",
            -1e-4 to "-0.0001",
            1e-4 to "0.0001",
            1e16 to "1e+16",
            9007199254740992.0 to "9007199254740992.0",
            9007199254740994.0 to "9.007199254740994e+15",
            1e15 to "1000000000000000.0",
            1e-5 to "1e-05",
            1.5e-7 to "1.5e-07",
            0.1 to "0.1",
            0.0 to "0.0",
            -0.0 to "-0.0",
            3601.0 to "3601.0",
            1e300 to "1e+300",
            2e22 to "2e+22",
            1.2345678901234567e19 to "1.2345678901234567e+19",
            Double.MAX_VALUE to "1.7976931348623157e+308",
            5e-324 to "5e-324",
            1.23e-308 to "1.23e-308",
            Double.POSITIVE_INFINITY to "inf",
            Double.NEGATIVE_INFINITY to "-inf",
            Double.NaN to "nan",
            SwiftNumbers.NEGATIVE_NAN to "-nan",
            SwiftNumbers.SIGNALING_NAN to "snan",
        )
        for ((value, text) in cases) assertEquals(text, SwiftNumbers.description(value), "$value")
    }

    /** What Swift's `Double(_:)` reads, and what it refuses (`nil`), as `swift` printed them. */
    @Test
    fun aDoubleIsReadAsSwiftReadsIt() {
        val read = listOf(
            "0.5" to "0.5", ".5" to "0.5", "5." to "5.0", "+5" to "5.0", "1E5" to "100000.0", "1e+5" to "100000.0",
            "1e400" to "inf", "0x10" to "16.0", "0X1F" to "31.0", "0x10f" to "271.0", "0x10d" to "269.0", "0x1e" to "30.0",
            "0x1p-1" to "0.5", "0X1P4" to "16.0", "0x1.8" to "1.5", "0x.8" to "0.5", "-0x10" to "-16.0", "+0x10" to "16.0",
            "inf" to "inf", "+inf" to "inf", "-inf" to "-inf", "INF" to "inf", "infinity" to "inf", "Infinity" to "inf",
            "nan" to "nan", "+nan" to "nan", "-nan" to "-nan", "nan(0x1)" to "nan", "snan" to "snan", "SNAN" to "snan", "-snan" to "snan",
        )
        for ((text, value) in read) assertEquals(value, SwiftNumbers.double(text)?.let(SwiftNumbers::description), "'$text'")
        val refused = listOf(
            "", " 0.5", "0.5 ", "\t0.5", "0.5\n", "\u00010.5", "0.5\u0001", "0.5\u007f", "0.5d", "0.5f", "0.5D", "1_0", "1e", "0x",
            "0x1p", ".", "-", "e5", "0x1.8p", "--1", "+-1", "1.0.0", "\u0663",
        )
        for (text in refused) assertEquals(null, SwiftNumbers.double(text), "'$text'")
    }
}
