package ru.hznik.devicebridge.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.server.MonotonicClock

class SessionMemoryBoundsTest {

    @Test
    fun hostileSourceSeriesCannotGrowRateLimitRecordsPastCapacity() {
        val limiter = PairingRateLimiter(FixedClock(1_000), maxRecords = 3)

        repeat(100) { index -> limiter.recordFailure("10.0.0.${index + 1}") }

        assertTrue(limiter.trackedSourceCount <= 3)
    }

    @Test
    fun challengeRegistryRejectsCapacityAndRemovesExpiredEntries() {
        val clock = MutableClock(1_000)
        val challenges = BoundedExpiringRegistry<String, String>(clock, maxEntries = 2)

        assertEquals(RegistryPutResult.ADDED, challenges.put("c1", "one", expiresAtMs = 2_000))
        assertEquals(RegistryPutResult.ADDED, challenges.put("c2", "two", expiresAtMs = 3_000))
        assertEquals(
            RegistryPutResult.CAPACITY_REACHED,
            challenges.put("c3", "three", expiresAtMs = 4_000),
        )

        clock.now = 2_000
        assertNull(challenges.get("c1"))
        assertEquals(RegistryPutResult.ADDED, challenges.put("c3", "three", expiresAtMs = 4_000))
        assertEquals(2, challenges.size)
    }

    @Test
    fun pendingRegistryHasAnIndependentStrictCapacity() {
        val clock = FixedClock(1_000)
        val pending = BoundedExpiringRegistry<String, String>(clock, maxEntries = 1)

        assertEquals(RegistryPutResult.ADDED, pending.put("r1", "request", 61_000))
        assertEquals(
            RegistryPutResult.CAPACITY_REACHED,
            pending.put("r2", "request", 61_000),
        )
        assertEquals("request", pending.remove("r1"))
        assertEquals(RegistryPutResult.ADDED, pending.put("r2", "request", 61_000))
    }

    private open class FixedClock(private val fixedNow: Long) : MonotonicClock {
        override fun nowMs(): Long = fixedNow
    }

    private class MutableClock(var now: Long) : MonotonicClock {
        override fun nowMs(): Long = now
    }
}
