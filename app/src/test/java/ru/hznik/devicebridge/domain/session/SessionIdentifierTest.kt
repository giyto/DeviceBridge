package ru.hznik.devicebridge.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionIdentifierTest {

    @Test
    fun identifiersUseValueEqualityWithinTheirOwnType() {
        assertEquals(ServerGenerationId(3), ServerGenerationId(3))
        assertEquals(PairingRequestId("same"), PairingRequestId("same"))
        assertEquals(BrowserSessionId("same"), BrowserSessionId("same"))
        assertEquals(PairingChallengeId("same"), PairingChallengeId("same"))

        assertNotEquals(PairingRequestId("same") as Any, BrowserSessionId("same") as Any)
        assertFalse(ServerGenerationId(3).equals(PairingRequestId("3") as Any))
    }

    @Test
    fun identifiersRejectEmptyControlAndOversizedValues() {
        assertThrows(IllegalArgumentException::class.java) { ServerGenerationId(0) }
        assertThrows(IllegalArgumentException::class.java) { PairingRequestId(" ") }
        assertThrows(IllegalArgumentException::class.java) { BrowserSessionId("bad\nsession") }
        assertThrows(IllegalArgumentException::class.java) { PairingChallengeId("x".repeat(65)) }
    }
}
