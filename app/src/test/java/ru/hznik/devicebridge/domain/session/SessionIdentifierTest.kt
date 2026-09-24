package ru.hznik.devicebridge.domain.session

import org.junit.Assert.assertThrows
import org.junit.Test

class SessionIdentifierTest {

    @Test
    fun identifiersRejectEmptyControlAndOversizedValues() {
        assertThrows(IllegalArgumentException::class.java) { ServerGenerationId(0) }
        assertThrows(IllegalArgumentException::class.java) { PairingRequestId(" ") }
        assertThrows(IllegalArgumentException::class.java) { BrowserSessionId("bad\nsession") }
        assertThrows(IllegalArgumentException::class.java) { PairingChallengeId("x".repeat(65)) }
    }
}
