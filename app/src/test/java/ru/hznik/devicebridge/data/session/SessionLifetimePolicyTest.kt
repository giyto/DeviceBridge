package ru.hznik.devicebridge.data.session

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.data.session.security.CryptographicRandom
import ru.hznik.devicebridge.data.session.security.SessionSecretGenerator

class SessionLifetimePolicyTest {

    @Test
    fun pairingCodeLivesFiveMinutesAndRotatesAtExpiry() {
        val clock = MutableClock(10_000)
        val random = SequenceRandom(123456, 654321)
        val policy = SessionLifetimePolicy(clock, SessionSecretGenerator(random))
        val first = policy.newPairingCode()

        assertEquals("123456", first.value)
        assertEquals(310_000, first.expiresAtElapsedRealtimeMs)
        clock.nowMs = 309_999
        assertTrue(policy.isPairingCodeActive(first))
        assertEquals(first, policy.currentOrRotated(first))

        clock.nowMs = 310_000
        assertFalse(policy.isPairingCodeActive(first))
        val rotated = policy.currentOrRotated(first)
        assertNotEquals(first.value, rotated.value)
        assertEquals("654321", rotated.value)
        assertEquals(610_000, rotated.expiresAtElapsedRealtimeMs)
    }

    @Test
    fun pendingRequestTimesOutAfterSixtySeconds() {
        val clock = MutableClock(5_000)
        val policy = SessionLifetimePolicy(clock, SessionSecretGenerator(SequenceRandom(1)))
        val expiry = policy.newPendingExpiry()

        assertEquals(65_000, expiry)
        clock.nowMs = 64_999
        assertFalse(policy.isExpired(expiry))
        clock.nowMs = 65_000
        assertTrue(policy.isExpired(expiry))
    }

    @Test
    fun policyDoesNotReadWallClock() {
        val source = Files.readString(
            Path.of("src/main/java/ru/hznik/devicebridge/data/session/SessionLifetimePolicy.kt"),
        )

        assertFalse(source.contains("currentTimeMillis"))
        assertFalse(source.contains("Instant.now"))
    }

    private class MutableClock(var nowMs: Long) : MonotonicClock {
        override fun nowMs(): Long = nowMs
    }

    private class SequenceRandom(vararg values: Int) : CryptographicRandom {
        private val iterator = values.iterator()

        override fun nextInt(bound: Int): Int = iterator.nextInt()

        override fun nextBytes(size: Int): ByteArray = ByteArray(size)
    }
}
