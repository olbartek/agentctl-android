package io.github.olbartek.agentctl.cli

import java.math.BigDecimal
import kotlin.math.abs

/**
 * Numbers as agentctl-ios's Swift reads and prints them, for the CLI's options and the messages that quote them: the
 * two ports then accept the same command lines and print the same values.
 */
internal object SwiftNumbers {
    /** A NaN with its sign bit set: what Swift's `Double("-nan")` is, printed `-nan`. */
    val NEGATIVE_NAN: Double = Double.fromBits(0x7FF8000000000000L or Long.MIN_VALUE)

    /** A signaling NaN (its quiet bit clear): what Swift's `Double("snan")` is, printed `snan`. */
    val SIGNALING_NAN: Double = Double.fromBits(0x7FF4000000000000L)

    private const val QUIET_BIT: Long = 1L shl 51

    /** 2^53: Swift writes a larger magnitude in exponent form. */
    private const val LARGEST_PLAIN: Double = 9007199254740992.0

    /** Swift writes a smaller (nonzero) magnitude in exponent form. */
    private const val SMALLEST_PLAIN: Double = 1e-4

    /**
     * [value] as Swift's string interpolation writes a `Double`: the shortest digits that read back as it (Java's
     * [Double.toString] finds the same ones), laid out as `10000000.0`, `0.0001`, `1e+16`, `1e-05`, `inf`, `-nan`, `snan`.
     */
    fun description(value: Double): String {
        val bits = value.toRawBits()
        val sign = if (bits < 0) "-" else ""
        if (value.isNaN()) return sign + if (bits and QUIET_BIT == 0L) "snan" else "nan"
        if (value.isInfinite()) return sign + "inf"
        val magnitude = abs(value)
        if (magnitude == 0.0) return sign + "0.0"
        val decimal = BigDecimal(magnitude.toString()).stripTrailingZeros()
        val digits = decimal.unscaledValue().toString()
        // The value is d.ddd × 10^exponent.
        val exponent = digits.length - 1 - decimal.scale()
        if (magnitude > LARGEST_PLAIN || magnitude < SMALLEST_PLAIN) {
            val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            return sign + mantissa + "e" + (if (exponent < 0) "-" else "+") + abs(exponent).toString().padStart(2, '0')
        }
        val plain = when {
            exponent < 0 -> "0." + "0".repeat(-exponent - 1) + digits
            digits.length <= exponent + 1 -> digits + "0".repeat(exponent + 1 - digits.length) + ".0"
            else -> digits.substring(0, exponent + 1) + "." + digits.substring(exponent + 1)
        }
        return sign + plain
    }
}
