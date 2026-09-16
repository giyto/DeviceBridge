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
import ru.hznik.devicebridge.domain.session.ServerGenerationId

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSessionCoordinatorRegistryTest {

    @Test
    fun bearerLookupReturnsMetadataAndRevokeClosesBoundConnection() = runTest {
        val coordinator = BrowserSessionCoordinator(
            clock = FixedClock(1_000),
            secretGenerator = SessionSecretGenerator(DeterministicRandom()),
            scope = backgroundScope,
        )
        val handle = coordinator.activate(ServerGenerationId(1))
        val challenge = coordinator.createChallenge(handle, "Chrome", "192.168.1.2")
            as ChallengeCreationResult.Created
        val response = async {
            coordinator.confirmAndAwait(
                handle,
                challenge.challengeId,
                "123456",
                "Chrome",
                "192.168.1.2",
            )
        }
        runCurrent()
        coordinator.approve(coordinator.state.value.pendingRequests.single().id)
        val approved = response.await() as SessionConfirmationResult.Approved
        val connection = RecordingConnection()

        assertEquals(
            approved.sessionId,
            coordinator.authenticate(handle, approved.token)?.id,
        )
        assertTrue(coordinator.attachConnection(approved.sessionId, connection))
        assertFalse(connection.closed)

        coordinator.revoke(approved.sessionId)

        assertNull(coordinator.authenticate(handle, approved.token))
        assertTrue(connection.closed)
        assertTrue(coordinator.state.value.sessions.isEmpty())
    }

    @Test
    fun malformedUnknownAndOldGenerationTokensAreRejected() = runTest {
        val coordinator = BrowserSessionCoordinator(
            clock = FixedClock(1_000),
            secretGenerator = SessionSecretGenerator(DeterministicRandom()),
            scope = backgroundScope,
        )
        val oldHandle = coordinator.activate(ServerGenerationId(1))
        val newHandle = coordinator.activate(ServerGenerationId(2))

        assertNull(coordinator.authenticate(newHandle, ""))
        assertNull(coordinator.authenticate(newHandle, "unknown"))
        assertNull(coordinator.authenticate(oldHandle, "unknown"))
    }

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
        private var seed = 1

        override fun nextInt(bound: Int): Int = 123456

        override fun nextBytes(size: Int): ByteArray = ByteArray(size) { (seed++).toByte() }
    }
}
