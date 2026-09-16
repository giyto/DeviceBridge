package ru.hznik.devicebridge.data.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.data.session.security.CryptographicRandom
import ru.hznik.devicebridge.data.session.security.SessionSecretGenerator
import ru.hznik.devicebridge.domain.session.BrowserSessionPhase
import ru.hznik.devicebridge.domain.session.ServerGenerationId

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSessionCoordinatorCleanupTest {

    @Test
    fun closeGenerationInvalidatesPendingSessionsTokensTimersAndConnections() = runTest {
        val coordinator = coordinator()
        val handle = coordinator.activate(ServerGenerationId(1))
        val approved = pair(coordinator, handle, "Chrome", "192.168.1.2", "123456")
        val connection = RecordingConnection()
        coordinator.attachConnection(approved.sessionId, connection)

        val pendingChallenge = coordinator.createChallenge(handle, "Edge", "192.168.1.3")
            as ChallengeCreationResult.Created
        val pendingResponse = async {
            coordinator.confirmAndAwait(
                handle,
                pendingChallenge.challengeId,
                "654321",
                "Edge",
                "192.168.1.3",
            )
        }
        runCurrent()
        assertEquals(1, coordinator.state.value.pendingRequests.size)

        coordinator.closeGeneration(handle)

        assertEquals(SessionConfirmationResult.GenerationClosed, pendingResponse.await())
        assertEquals(BrowserSessionPhase.INACTIVE, coordinator.state.value.phase)
        assertNull(coordinator.authenticate(handle, approved.token))
        assertTrue(connection.closed)
        assertFalse(coordinator.isCurrent(handle))
    }

    @Test
    fun staleCloseCannotAffectNewGeneration() = runTest {
        val coordinator = coordinator()
        val oldHandle = coordinator.activate(ServerGenerationId(1))
        val currentHandle = coordinator.activate(ServerGenerationId(2))
        val currentCode = coordinator.state.value.pairingCode

        coordinator.closeGeneration(oldHandle)

        assertTrue(coordinator.isCurrent(currentHandle))
        assertEquals(ServerGenerationId(2), coordinator.state.value.generationId)
        assertEquals(currentCode, coordinator.state.value.pairingCode)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.pair(
        coordinator: BrowserSessionCoordinator,
        handle: SessionGenerationHandle,
        label: String,
        sourceIpv4: String,
        code: String,
    ): SessionConfirmationResult.Approved {
        val challenge = coordinator.createChallenge(handle, label, sourceIpv4)
            as ChallengeCreationResult.Created
        val result = async {
            coordinator.confirmAndAwait(handle, challenge.challengeId, code, label, sourceIpv4)
        }
        runCurrent()
        coordinator.approve(coordinator.state.value.pendingRequests.single().id)
        return result.await() as SessionConfirmationResult.Approved
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator() = BrowserSessionCoordinator(
        clock = FixedClock(1_000),
        secretGenerator = SessionSecretGenerator(DeterministicRandom()),
        scope = backgroundScope,
    )

    private class RecordingConnection : SessionConnection {
        var closed = false
        override suspend fun close() {
            closed = true
        }
    }

    private class FixedClock(private val now: Long) : MonotonicClock {
        override fun nowMs(): Long = now
    }

    private class DeterministicRandom : CryptographicRandom {
        private val codes = listOf(123456, 654321, 777777, 888888).iterator()
        private var seed = 1

        override fun nextInt(bound: Int): Int = codes.next()

        override fun nextBytes(size: Int): ByteArray = ByteArray(size) { (seed++).toByte() }
    }
}
