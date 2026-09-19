package ru.hznik.devicebridge.domain.error

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureTechnicalDetailsTest {

    @Test
    fun technicalDetailsContainOnlyStableAllowlistedInformation() {
        val failure = UserFacingFailure(
            code = FailureCode.NETWORK_LOST,
            severity = FailureSeverity.RECOVERABLE,
            recoveryActions = setOf(RecoveryAction.CONNECT_TO_LOCAL_NETWORK),
            context = FailureContext.fromUntrusted(
                mapOf(
                    "operationId" to "operation-42",
                    "lifecycleState" to "running",
                    "sessionToken" to "bearer-secret",
                    "path" to "C:\\private\\video.mp4",
                    "content" to "private message",
                ),
            ),
        )

        val details = failure.toTechnicalDetailsText()

        assertTrue(details.contains("Код: network_lost"))
        assertTrue(details.contains("Состояние: recoverable"))
        assertTrue(details.contains("operationId: operation-42"))
        assertTrue(details.contains("lifecycleState: running"))
        assertFalse(details.contains("bearer-secret"))
        assertFalse(details.contains("private\\video.mp4"))
        assertFalse(details.contains("private message"))
    }
}
