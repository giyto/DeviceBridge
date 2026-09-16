package ru.hznik.devicebridge.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSessionModelsTest {

    @Test
    fun pairingCodeRequiresExactlySixDigitsAndPositiveExpiry() {
        val code = PairingCodeState("004219", expiresAtElapsedRealtimeMs = 300_000)

        assertEquals("004219", code.value)
        assertThrows(IllegalArgumentException::class.java) {
            PairingCodeState("4219", expiresAtElapsedRealtimeMs = 300_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingCodeState("abcdef", expiresAtElapsedRealtimeMs = 300_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingCodeState("004219", expiresAtElapsedRealtimeMs = 0)
        }
    }

    @Test
    fun pendingRequestAndSessionRequireBoundedDisplayMetadata() {
        val generationId = ServerGenerationId(7)
        val request = PendingBrowserRequest(
            id = PairingRequestId("request-1"),
            challengeId = PairingChallengeId("challenge-1"),
            generationId = generationId,
            browserLabel = "Edge on PC",
            sourceIpv4 = "192.168.1.20",
            createdAtElapsedRealtimeMs = 1_000,
            expiresAtElapsedRealtimeMs = 61_000,
        )
        val session = BrowserSession(
            id = BrowserSessionId("session-1"),
            generationId = generationId,
            browserLabel = request.browserLabel,
            sourceIpv4 = request.sourceIpv4,
            connectedAtElapsedRealtimeMs = 2_000,
        )

        assertEquals(generationId, request.generationId)
        assertEquals("Edge on PC", session.browserLabel)
        assertThrows(IllegalArgumentException::class.java) {
            request.copy(browserLabel = "bad\nlabel")
        }
        assertThrows(IllegalArgumentException::class.java) {
            session.copy(sourceIpv4 = "not-an-ip")
        }
    }

    @Test
    fun stateIsImmutableAndNeverContainsRawSessionToken() {
        val generationId = ServerGenerationId(7)
        val state = BrowserSessionState.active(
            generationId = generationId,
            pairingCode = PairingCodeState("004219", 300_000),
        )

        assertTrue(state.isActive)
        assertEquals(BrowserSessionPhase.READY, state.phase)
        assertTrue(state.pendingRequests.isEmpty())
        assertTrue(state.sessions.isEmpty())

        val inactive = BrowserSessionState.inactive()
        assertFalse(inactive.isActive)
        assertEquals(BrowserSessionPhase.INACTIVE, inactive.phase)

        val exposedNames = BrowserSession::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(exposedNames.any { it.contains("token") })
    }

    @Test
    fun activeStateRejectsCrossGenerationChildren() {
        val stateGeneration = ServerGenerationId(7)
        val otherGeneration = ServerGenerationId(8)
        val foreignSession = BrowserSession(
            id = BrowserSessionId("session-1"),
            generationId = otherGeneration,
            browserLabel = "Chrome",
            sourceIpv4 = "10.0.0.2",
            connectedAtElapsedRealtimeMs = 2_000,
        )

        assertThrows(IllegalArgumentException::class.java) {
            BrowserSessionState.active(
                generationId = stateGeneration,
                pairingCode = PairingCodeState("123456", 300_000),
                sessions = listOf(foreignSession),
            )
        }
    }
}
