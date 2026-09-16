package ru.hznik.devicebridge.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.server.MonotonicClock

class PairingRateLimiterTest {

    @Test
    fun fifthFailureBlocksSourceForSixtySeconds() {
        val clock = MutableClock(1_000)
        val limiter = PairingRateLimiter(clock)
        val source = "192.168.1.20"

        repeat(4) { index ->
            assertEquals(
                RateLimitDecision.Allowed(remainingAttempts = 4 - index),
                limiter.recordFailure(source),
            )
        }

        assertEquals(
            RateLimitDecision.Blocked(retryAfterMs = 60_000),
            limiter.recordFailure(source),
        )
        clock.now = 60_999
        assertEquals(
            RateLimitDecision.Blocked(retryAfterMs = 1),
            limiter.check(source),
        )
        clock.now = 61_000
        assertEquals(
            RateLimitDecision.Allowed(remainingAttempts = 5),
            limiter.check(source),
        )
    }

    @Test
    fun sourcesHaveIndependentFailureBudgetsAndSuccessClearsOneSource() {
        val clock = MutableClock(5_000)
        val limiter = PairingRateLimiter(clock)

        assertEquals(
            RateLimitDecision.Allowed(remainingAttempts = 4),
            limiter.recordFailure("10.0.0.2"),
        )
        assertEquals(
            RateLimitDecision.Allowed(remainingAttempts = 5),
            limiter.check("10.0.0.3"),
        )

        limiter.recordSuccess("10.0.0.2")

        assertEquals(
            RateLimitDecision.Allowed(remainingAttempts = 5),
            limiter.check("10.0.0.2"),
        )
        assertTrue(limiter.trackedSourceCount <= 1)
    }

    private class MutableClock(var now: Long) : MonotonicClock {
        override fun nowMs(): Long = now
    }
}
