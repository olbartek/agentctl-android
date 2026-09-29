package io.github.olbartek.agentctl.examples.agentshop.models

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Formats integer cents as dollars without a locale, so output is identical on every machine: `5980` → `$59.80`. */
fun formatCents(cents: Int): String {
    val sign = if (cents < 0) "-" else ""
    val magnitude = Math.abs(cents.toLong())
    val fraction = magnitude % 100
    return "$sign$${magnitude / 100}.${if (fraction < 10) "0" else ""}$fraction"
}

/** Formats a date as `yyyy-MM-dd` in UTC. */
fun formatDay(date: Instant): String = date.atOffset(ZoneOffset.UTC).toLocalDate().toString()

/** Parses `yyyy-MM-dd` as midnight UTC. Throws on malformed input; meant for seed data and tests. */
fun day(text: String): Instant = LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC)
