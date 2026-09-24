package io.github.olbartek.agentctl

import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking

class MockingTest {
    class Boom(val code: String) : Exception(code)

    /** A clock that must not be used: zero latency never sleeps. */
    private object UnimplementedClock : AgentClock {
        override fun now(): Instant = error("now() was read")
        override suspend fun sleep(duration: Duration) = error("sleep($duration) was called")
        override val activeSleeps: Int get() = 0
    }

    @Test
    fun callLogRecordsEntriesAndInFlight() {
        val log = MockCallLog()
        log.begin("auth.login")
        assertEquals(1, log.inFlight)
        log.begin("session.save")
        log.end("session.save")
        log.end("auth.login")
        assertEquals(listOf("auth.login", "session.save"), log.all)
        assertEquals(0, log.inFlight)
        assertEquals(listOf("session.save"), log.since(1))
        assertEquals(2, log.count)
    }

    @Test
    fun faultsAreOneShot() {
        val faults = MockFaults()
        faults.set("orders.fetchOrders", "network")
        assertEquals(mapOf("orders.fetchOrders" to "network"), faults.pending)
        assertEquals("network", faults.take("orders.fetchOrders"))
        assertEquals(null, faults.take("orders.fetchOrders"))
    }

    @Test
    fun latencySampling() {
        assertEquals(Duration.ZERO, MockLatency.ZERO.sample(Random))
        assertEquals(300.milliseconds, MockLatency.fixed(300.milliseconds).sample(Random))
        repeat(20) {
            val delay = MockLatency.between(300.milliseconds, 800.milliseconds).sample(Random)
            assertTrue(delay in 300.milliseconds..800.milliseconds)
        }
        assertEquals(MockLatency.ZERO, MockLatency.milliseconds(0))
    }

    @Test
    fun callWithZeroLatencyNeverTouchesTheClock() = runBlocking {
        val log = MockCallLog()
        val backend = MockBackend(log, MockFaults(), MockLatency.ZERO, UnimplementedClock, Random)
        assertEquals(42, backend.call("demo.fetch", ::Boom) { 42 })
        assertEquals(listOf("demo.fetch"), log.all)
        assertEquals(0, log.inFlight)
    }

    @Test
    fun callThrowsTheRegisteredFaultOnce() = runBlocking {
        val faults = MockFaults()
        faults.set("demo.fetch", "network")
        val backend = MockBackend(MockCallLog(), faults, MockLatency.ZERO, UnimplementedClock, Random)
        assertEquals("network", assertFailsWith<Boom> { backend.call("demo.fetch", ::Boom) { 1 } }.code)
        assertEquals(2, backend.call("demo.fetch", ::Boom) { 2 })
    }

    @Test
    fun splitMix64IsTheReferenceSequence() {
        // SplitMix64 with seed 0: the first outputs every implementation of the algorithm produces.
        val random = SplitMix64Random(0)
        assertEquals(listOf(-2152535657050944081L, 7960286522194355700L, 487617019471545679L), List(3) { random.nextLong() })
    }

    @Test
    fun incrementingUuids() {
        val uuids = IncrementingUuids()
        assertEquals("00000000-0000-0000-0000-000000000000", uuids().toString())
        assertEquals("00000000-0000-0000-0000-000000000001", uuids().toString())
    }
}
