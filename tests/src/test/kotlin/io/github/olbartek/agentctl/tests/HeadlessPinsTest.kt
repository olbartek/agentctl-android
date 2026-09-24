package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.runtime.HeadlessHost
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/** What this guards: the deterministic environment of CONTRACT.md §6, as a headless host hands it to the app. */
class HeadlessPinsTest {
    @Test
    fun everyPinnedValue() {
        val environment = TinyAppConfig.headless().environment
        assertEquals(Instant.parse("2026-01-01T09:00:00Z"), environment.clock.now())
        assertEquals(environment.clock.now(), environment.clock.now())
        assertEquals("00000000-0000-0000-0000-000000000000", environment.uuids().toString())
        assertEquals("00000000-0000-0000-0000-000000000001", environment.uuids().toString())
        assertEquals(ZoneOffset.UTC, environment.zone)
        assertEquals("en_US_POSIX", environment.locale.toString())
    }

    @Test
    fun randomnessIsTheSameInEveryHost() {
        val first = TinyAppConfig.headless().environment.random
        val second = TinyAppConfig.headless().environment.random
        assertEquals(List(5) { first.nextInt() }, List(5) { second.nextInt() })
        assertEquals(0L, HeadlessHost.RANDOM_SEED)
    }

    @Test
    fun virtualTimeOnlyMovesWhenAdvanced() {
        val host = TinyAppConfig.headless()
        host.makeRunner()
        assertEquals(0, host.dispatcher.currentTime)
        host.settle()
        assertEquals(0, host.dispatcher.currentTime)
        host.dispatcher.advanceBy(1500)
        assertEquals(1500, host.dispatcher.currentTime)
        host.dispatcher.advanceBy(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, host.dispatcher.currentTime)
    }
}
