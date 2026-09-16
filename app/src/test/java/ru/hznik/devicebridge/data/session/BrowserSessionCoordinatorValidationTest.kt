package ru.hznik.devicebridge.data.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.data.session.security.CryptographicRandom
import ru.hznik.devicebridge.data.session.security.SessionSecretGenerator
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.session.BrowserSessionPhase

class BrowserSessionCoordinatorValidationTest {

    @Test
    fun wrongCodesConsumeBudgetAndFifthAttemptBlocksWithoutPendingRequest() = runTest {
        val clock = MutableClock(1_000)
        val coordinator = coordinator(clock)
        val handle = coordinator.activate(ServerGenerationId(1))
        val challenge = coordinator.createChallenge(handle, "Chrome", "192.168.1.2")
            as ChallengeCreationResult.Created

        repeat(4) { index ->
            assertEquals(
                SessionConfirmationResult.InvalidCode(remainingAttempts = 4 - index),
                coordinator.confirmAndAwait(
                    handle,
                    challenge.challengeId,
                    code = "000000",
                    browserLabel = "Chrome",
                    sourceIpv4 = "192.168.1.2",
                ),
            )
        }
        assertEquals(
            SessionConfirmationResult.RateLimited(retryAfterMs = 60_000),
            coordinator.confirmAndAwait(
                handle,
                challenge.challengeId,
                code = "000000",
                browserLabel = "Chrome",
                sourceIpv4 = "192.168.1.2",
            ),
        )
        assertTrue(coordinator.state.value.pendingRequests.isEmpty())
        assertTrue(coordinator.state.value.sessions.isEmpty())
    }

    @Test
    fun expiredCodeRotatesAndCannotCreatePendingRequest() = runTest {
        val clock = MutableClock(1_000)
        val coordinator = coordinator(clock)
        val handle = coordinator.activate(ServerGenerationId(1))
        val challenge = coordinator.createChallenge(handle, "Edge", "10.0.0.2")
            as ChallengeCreationResult.Created

        clock.now = 301_000
        val result = coordinator.confirmAndAwait(
            handle,
            challenge.challengeId,
            code = "123456",
            browserLabel = "Edge",
            sourceIpv4 = "10.0.0.2",
        )

        assertEquals(SessionConfirmationResult.Expired, result)
        assertEquals("654321", coordinator.state.value.pairingCode?.value)
        assertTrue(coordinator.state.value.pendingRequests.isEmpty())
        assertTrue(coordinator.state.value.sessions.isEmpty())
    }

    @Test
    fun blockStateClearsAfterRetryWindow() = runTest {
        val clock = MutableClock(1_000)
        val coordinator = coordinator(clock)
        val handle = coordinator.activate(ServerGenerationId(1))
        val challenge = coordinator.createChallenge(handle, "Chrome", "192.168.1.2")
            as ChallengeCreationResult.Created
        repeat(5) {
            coordinator.confirmAndAwait(
                handle,
                challenge.challengeId,
                "000000",
                "Chrome",
                "192.168.1.2",
            )
        }
        assertEquals(BrowserSessionPhase.BLOCKED, coordinator.state.value.phase)

        clock.now = 61_000
        assertTrue(
            coordinator.createChallenge(handle, "Chrome", "192.168.1.2")
                is ChallengeCreationResult.Created,
        )
        assertEquals(BrowserSessionPhase.READY, coordinator.state.value.phase)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(clock: MonotonicClock) =
        BrowserSessionCoordinator(
            clock = clock,
            secretGenerator = SessionSecretGenerator(DeterministicRandom()),
            scope = backgroundScope,
        )

    private class MutableClock(var now: Long) : MonotonicClock {
        override fun nowMs(): Long = now
    }

    private class DeterministicRandom : CryptographicRandom {
        private val codes = listOf(123456, 654321).iterator()
        private var seed = 1

        override fun nextInt(bound: Int): Int = codes.next()

        override fun nextBytes(size: Int): ByteArray = ByteArray(size) { (seed++).toByte() }
    }
}
