package ru.hznik.devicebridge.data.session

import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
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
class BrowserSessionCoordinatorDecisionTest {

    @Test
    fun correctCodeWaitsUntilExplicitApprovalAndReturnsTokenOnce() = runTest {
        val fixture = Fixture(this)
        val challenge = fixture.challenge("Chrome")
        val response = async {
            fixture.coordinator.confirmAndAwait(
                fixture.handle,
                challenge.challengeId,
                "123456",
                "Chrome",
                "192.168.1.2",
            )
        }
        runCurrent()
        val request = fixture.coordinator.state.value.pendingRequests.single()

        assertTrue(!response.isCompleted)
        fixture.coordinator.approve(request.id)

        val approved = response.await() as SessionConfirmationResult.Approved
        assertTrue(approved.token.isNotBlank())
        assertEquals(approved.sessionId, fixture.coordinator.state.value.sessions.single().id)
        assertEquals("654321", fixture.coordinator.state.value.pairingCode?.value)
    }

    @Test
    fun denialCompletesWithoutCreatingSessionOrToken() = runTest {
        val fixture = Fixture(this)
        val challenge = fixture.challenge("Edge")
        val response = async {
            fixture.coordinator.confirmAndAwait(
                fixture.handle,
                challenge.challengeId,
                "123456",
                "Edge",
                "192.168.1.2",
            )
        }
        runCurrent()

        fixture.coordinator.deny(fixture.coordinator.state.value.pendingRequests.single().id)

        assertEquals(SessionConfirmationResult.Denied, response.await())
        assertTrue(fixture.coordinator.state.value.sessions.isEmpty())
    }

    @Test
    fun allowAndRememberAndRepeatedDecisionCreateOnlyOneSession() = runTest {
        val fixture = Fixture(this)
        val challenge = fixture.challenge("Edge", rememberBrowser = true)
        val response = async {
            fixture.coordinator.confirmAndAwait(
                fixture.handle,
                challenge.challengeId,
                "123456",
                "Edge",
                "192.168.1.2",
            )
        }
        runCurrent()
        val request = fixture.coordinator.state.value.pendingRequests.single()

        fixture.coordinator.approveAndRemember(request.id)
        fixture.coordinator.approve(request.id)

        assertTrue(response.await() is SessionConfirmationResult.Approved)
        assertEquals(1, fixture.coordinator.state.value.sessions.size)
    }

    @Test
    fun recoveryObservesTheOriginalRequestAndReturnsTheSameApprovedSession() = runTest {
        val fixture = Fixture(this)
        val challenge = fixture.challenge("Chrome")
        val response = async {
            fixture.coordinator.confirmAndAwait(
                fixture.handle,
                challenge.challengeId,
                "123456",
                "Chrome",
                "192.168.1.2",
            )
        }
        runCurrent()

        assertEquals(
            SessionConfirmationRecoveryResult.Pending,
            fixture.coordinator.recoverConfirmation(
                fixture.handle,
                challenge.challengeId,
                "Chrome",
                "192.168.1.2",
            ),
        )
        fixture.coordinator.approve(fixture.coordinator.state.value.pendingRequests.single().id)

        val original = response.await() as SessionConfirmationResult.Approved
        val recovered = fixture.coordinator.recoverConfirmation(
            fixture.handle,
            challenge.challengeId,
            "Chrome",
            "192.168.1.2",
        ) as SessionConfirmationRecoveryResult.Approved

        assertEquals(original.sessionId, recovered.confirmation.sessionId)
        assertEquals(original.token, recovered.confirmation.token)
        assertEquals(1, fixture.coordinator.state.value.sessions.size)
    }

    @Test
    fun unansweredConfirmationTimesOutAndRemovesPendingRequest() = runTest {
        val fixture = Fixture(this)
        val challenge = fixture.challenge("Firefox")
        val response = async {
            fixture.coordinator.confirmAndAwait(
                fixture.handle,
                challenge.challengeId,
                "123456",
                "Firefox",
                "192.168.1.2",
            )
        }
        runCurrent()
        assertEquals(1, fixture.coordinator.state.value.pendingRequests.size)

        fixture.clock.now = 61_000
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(SessionConfirmationResult.TimedOut, response.await())
        assertTrue(fixture.coordinator.state.value.pendingRequests.isEmpty())
        assertTrue(fixture.coordinator.state.value.sessions.isEmpty())
    }

    private class Fixture(scope: kotlinx.coroutines.test.TestScope) {
        val clock = MutableClock(1_000)
        val coordinator = BrowserSessionCoordinator(
            clock = clock,
            secretGenerator = SessionSecretGenerator(DeterministicRandom()),
            scope = scope.backgroundScope,
        )
        lateinit var handle: SessionGenerationHandle

        suspend fun challenge(
            label: String = "Chrome",
            rememberBrowser: Boolean = false,
        ): ChallengeCreationResult.Created {
            handle = coordinator.activate(ServerGenerationId(1))
            return coordinator.createChallenge(handle, label, "192.168.1.2", rememberBrowser)
                as ChallengeCreationResult.Created
        }
    }

    private class MutableClock(var now: Long) : MonotonicClock {
        override fun nowMs(): Long = now
    }

    private class DeterministicRandom : CryptographicRandom {
        private val codes = listOf(123456, 654321, 777777).iterator()
        private var seed = 1

        override fun nextInt(bound: Int): Int = codes.next()

        override fun nextBytes(size: Int): ByteArray = ByteArray(size) { (seed++).toByte() }
    }
}
