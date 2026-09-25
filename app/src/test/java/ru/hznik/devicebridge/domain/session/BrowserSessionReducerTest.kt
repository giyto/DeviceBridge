package ru.hznik.devicebridge.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BrowserSessionReducerTest {
    private val generation = ServerGenerationId(4)
    private val code = PairingCodeState("123456", 300_000)
    private val nextCode = PairingCodeState("654321", 600_000)

    @Test
    fun reducerCoversReadyPendingConnectedBlockedErrorAndInactive() {
        val ready = BrowserSessionReducer.reduce(
            BrowserSessionState.inactive(),
            BrowserSessionEvent.Activated(generation, code),
        )
        assertEquals(BrowserSessionPhase.READY, ready.phase)

        val request = pendingRequest()
        val pending = BrowserSessionReducer.reduce(
            ready,
            BrowserSessionEvent.RequestAdded(request),
        )
        assertEquals(BrowserSessionPhase.PENDING, pending.phase)
        assertEquals(listOf(request), pending.pendingRequests)

        val session = browserSession()
        val connected = BrowserSessionReducer.reduce(
            pending,
            BrowserSessionEvent.RequestApproved(
                generationId = generation,
                requestId = request.id,
                session = session,
                nextPairingCode = nextCode,
            ),
        )
        assertEquals(BrowserSessionPhase.CONNECTED, connected.phase)
        assertEquals(nextCode, connected.pairingCode)
        assertEquals(emptyList<PendingBrowserRequest>(), connected.pendingRequests)
        assertEquals(listOf(session), connected.sessions)

        val blocked = BrowserSessionReducer.reduce(
            connected,
            BrowserSessionEvent.SourceBlocked(generation, blockedUntilElapsedRealtimeMs = 65_000),
        )
        assertEquals(BrowserSessionPhase.BLOCKED, blocked.phase)

        val unblocked = BrowserSessionReducer.reduce(
            blocked,
            BrowserSessionEvent.SourceBlockCleared(generation),
        )
        assertEquals(BrowserSessionPhase.CONNECTED, unblocked.phase)

        val inactive = BrowserSessionReducer.reduce(
            unblocked,
            BrowserSessionEvent.Deactivated(generation),
        )
        assertEquals(BrowserSessionPhase.INACTIVE, inactive.phase)
    }

    @Test
    fun reducerRejectsStaleAndInvalidTransitions() {
        val active = BrowserSessionState.active(generation, code)
        val staleGeneration = ServerGenerationId(99)

        assertSame(
            active,
            BrowserSessionReducer.reduce(
                active,
                BrowserSessionEvent.PairingCodeRotated(staleGeneration, nextCode),
            ),
        )
        assertSame(
            active,
            BrowserSessionReducer.reduce(
                active,
                BrowserSessionEvent.RequestApproved(
                    generationId = generation,
                    requestId = PairingRequestId("missing"),
                    session = browserSession(),
                    nextPairingCode = nextCode,
                ),
            ),
        )
        assertSame(
            active,
            BrowserSessionReducer.reduce(
                active,
                BrowserSessionEvent.RequestAdded(pendingRequest(generationId = staleGeneration)),
            ),
        )
    }

    @Test
    fun denyAndRevokeAffectOnlyTheAddressedObject() {
        val firstRequest = pendingRequest(id = "request-1")
        val secondRequest = pendingRequest(id = "request-2")
        val firstSession = browserSession(id = "session-1")
        val secondSession = browserSession(id = "session-2")
        val initial = BrowserSessionState.active(
            generationId = generation,
            pairingCode = code,
            pendingRequests = listOf(firstRequest, secondRequest),
            sessions = listOf(firstSession, secondSession),
        )

        val denied = BrowserSessionReducer.reduce(
            initial,
            BrowserSessionEvent.RequestDenied(generation, firstRequest.id),
        )
        val revoked = BrowserSessionReducer.reduce(
            denied,
            BrowserSessionEvent.SessionRevoked(generation, secondSession.id),
        )

        assertEquals(listOf(secondRequest), revoked.pendingRequests)
        assertEquals(listOf(firstSession), revoked.sessions)
    }

    private fun pendingRequest(
        id: String = "request-1",
        generationId: ServerGenerationId = generation,
    ) = PendingBrowserRequest(
        id = PairingRequestId(id),
        challengeId = PairingChallengeId("challenge-$id"),
        generationId = generationId,
        browserLabel = "Chrome",
        sourceIpv4 = "192.168.1.2",
        createdAtElapsedRealtimeMs = 1_000,
        expiresAtElapsedRealtimeMs = 61_000,
    )

    private fun browserSession(id: String = "session-1") = BrowserSession(
        id = BrowserSessionId(id),
        generationId = generation,
        browserLabel = "Chrome",
        sourceIpv4 = "192.168.1.2",
        connectedAtElapsedRealtimeMs = 2_000,
    )
}
