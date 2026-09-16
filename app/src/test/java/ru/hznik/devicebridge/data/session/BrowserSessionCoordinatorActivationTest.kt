package ru.hznik.devicebridge.data.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.data.session.security.CryptographicRandom
import ru.hznik.devicebridge.data.session.security.SessionSecretGenerator
import ru.hznik.devicebridge.domain.session.BrowserSessionPhase
import ru.hznik.devicebridge.domain.session.ServerGenerationId

class BrowserSessionCoordinatorActivationTest {

    @Test
    fun coordinatorPublishesOneGenerationAndCreatesFreshCodeOnEveryActivation() = runTest {
        val clock = FixedClock(1_000)
        val coordinator = BrowserSessionCoordinator(
            clock = clock,
            secretGenerator = SessionSecretGenerator(SequenceRandom(111111, 222222)),
            scope = backgroundScope,
        )

        assertEquals(BrowserSessionPhase.INACTIVE, coordinator.state.value.phase)
        val firstHandle = coordinator.activate(ServerGenerationId(1))
        val firstState = coordinator.state.value
        val secondHandle = coordinator.activate(ServerGenerationId(2))
        val secondState = coordinator.state.value

        assertEquals("111111", firstState.pairingCode?.value)
        assertEquals("222222", secondState.pairingCode?.value)
        assertEquals(ServerGenerationId(2), secondState.generationId)
        assertNotEquals(firstHandle, secondHandle)
        assertFalse(coordinator.isCurrent(firstHandle))
        assertTrue(coordinator.isCurrent(secondHandle))
    }

    private class FixedClock(private val now: Long) : MonotonicClock {
        override fun nowMs(): Long = now
    }

    private class SequenceRandom(vararg values: Int) : CryptographicRandom {
        private val iterator = values.iterator()

        override fun nextInt(bound: Int): Int = iterator.nextInt()

        override fun nextBytes(size: Int): ByteArray = ByteArray(size)
    }
}
