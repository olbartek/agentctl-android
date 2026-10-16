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
}
