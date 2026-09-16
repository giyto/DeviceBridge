package ru.hznik.devicebridge.data.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.data.session.security.CryptographicRandom
import ru.hznik.devicebridge.data.session.security.SessionSecretGenerator
import ru.hznik.devicebridge.domain.session.ServerGenerationId

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSessionCoordinatorConcurrencyTest {

    @Test
    fun decisionsAreBoundToExactRequestAndDuplicateDecisionIsIdempotent() = runTest {
        val coordinator = BrowserSessionCoordinator(
            clock = FixedClock(1_000),
            secretGenerator = SessionSecretGenerator(DeterministicRandom()),
            scope = backgroundScope,
        )
        val handle = coordinator.activate(ServerGenerationId(1))
        val chromeChallenge = coordinator.createChallenge(handle, "Chrome", "192.168.1.2")
            as ChallengeCreationResult.Created
        val edgeChallenge = coordinator.createChallenge(handle, "Edge", "192.168.1.3")
            as ChallengeCreationResult.Created
        val chromeResponse = async {
            coordinator.confirmAndAwait(
                handle,
                chromeChallenge.challengeId,
                "123456",
                "Chrome",
                "192.168.1.2",
            )
        }
        val edgeResponse = async {
            coordinator.confirmAndAwait(
                handle,
                edgeChallenge.challengeId,
                "123456",
                "Edge",
                "192.168.1.3",
            )
        }
        runCurrent()
        val requests = coordinator.state.value.pendingRequests.associateBy { it.browserLabel }
        val chromeRequest = requireNotNull(requests["Chrome"])
        val edgeRequest = requireNotNull(requests["Edge"])

        coordinator.approve(chromeRequest.id)
        coordinator.approve(chromeRequest.id)
        coordinator.deny(chromeRequest.id)

        assertTrue(chromeResponse.await() is SessionConfirmationResult.Approved)
        assertEquals(1, coordinator.state.value.sessions.size)
        assertEquals("Chrome", coordinator.state.value.sessions.single().browserLabel)
        assertTrue(!edgeResponse.isCompleted)

        coordinator.deny(edgeRequest.id)
        assertEquals(SessionConfirmationResult.Denied, edgeResponse.await())
        assertEquals(1, coordinator.state.value.sessions.size)
    }

    private class FixedClock(private val now: Long) : MonotonicClock {
        override fun nowMs(): Long = now
    }

    private class DeterministicRandom : CryptographicRandom {
        private var seed = 1

        override fun nextInt(bound: Int): Int = if (seed < 100) 123456 else 654321

        override fun nextBytes(size: Int): ByteArray = ByteArray(size) { (seed++).toByte() }
    }
}
