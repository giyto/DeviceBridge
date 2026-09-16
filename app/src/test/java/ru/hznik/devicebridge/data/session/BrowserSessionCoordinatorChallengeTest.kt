package ru.hznik.devicebridge.data.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.data.session.security.CryptographicRandom
import ru.hznik.devicebridge.data.session.security.SessionSecretGenerator
import ru.hznik.devicebridge.domain.session.ServerGenerationId

class BrowserSessionCoordinatorChallengeTest {

    @Test
    fun challengeIsOpaqueBoundedAndContainsNoSecret() = runTest {
        val coordinator = coordinator(maxChallenges = 2)
        val handle = coordinator.activate(ServerGenerationId(1))

        val result = coordinator.createChallenge(handle, "Chrome", "192.168.1.2")

        assertTrue(result is ChallengeCreationResult.Created)
        result as ChallengeCreationResult.Created
        assertTrue(result.challengeId.value.isNotBlank())
        assertEquals(301_000, result.expiresAtElapsedRealtimeMs)
        assertEquals(5, result.attemptsRemaining)
        val fieldNames = result.javaClass.declaredFields.map { it.name.lowercase() }
        assertFalse(fieldNames.any { it.contains("code") || it.contains("token") })
    }

    @Test
    fun staleHandleAndFullRegistryCannotCreateChallenge() = runTest {
        val coordinator = coordinator(maxChallenges = 1)
        val stale = coordinator.activate(ServerGenerationId(1))
        val current = coordinator.activate(ServerGenerationId(2))

        assertEquals(
            ChallengeCreationResult.GenerationClosed,
            coordinator.createChallenge(stale, "Chrome", "192.168.1.2"),
        )
        assertTrue(
            coordinator.createChallenge(current, "Chrome", "192.168.1.2")
                is ChallengeCreationResult.Created,
        )
        assertEquals(
            ChallengeCreationResult.CapacityReached,
            coordinator.createChallenge(current, "Edge", "192.168.1.3"),
        )
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(maxChallenges: Int) =
        BrowserSessionCoordinator(
            clock = FixedClock(1_000),
            secretGenerator = SessionSecretGenerator(DeterministicRandom()),
            scope = backgroundScope,
            maxChallenges = maxChallenges,
        )

    private class FixedClock(private val now: Long) : MonotonicClock {
        override fun nowMs(): Long = now
    }

    private class DeterministicRandom : CryptographicRandom {
        private var byteSeed: Byte = 1

        override fun nextInt(bound: Int): Int = 123456

        override fun nextBytes(size: Int): ByteArray = ByteArray(size) { byteSeed++ }
    }
}
