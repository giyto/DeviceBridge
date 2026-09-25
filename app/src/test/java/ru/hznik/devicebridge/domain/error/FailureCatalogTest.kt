package ru.hznik.devicebridge.domain.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.model.ServerLifecycleError

class FailureCatalogTest {

    @Test
    fun stableCodesAreUniqueAndWireSafe() {
        val wireValues = FailureCode.entries.map(FailureCode::wireValue)

        assertEquals(wireValues.size, wireValues.distinct().size)
        assertTrue(wireValues.all { it.matches(Regex("^[a-z][a-z0-9_]*$")) })
        assertEquals("unknown_error", FailureCode.UNKNOWN_ERROR.wireValue)
    }

    @Test
    fun mapsEveryLifecycleSourceToAStableFailure() {
        val cases = listOf(
            ServerLifecycleError.LocalNetworkPermissionDenied to expected(
                FailureCode.LOCAL_NETWORK_PERMISSION_DENIED,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.REQUEST_PERMISSION,
            ),
            ServerLifecycleError.PermissionRevoked to expected(
                FailureCode.LOCAL_NETWORK_PERMISSION_REVOKED,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.OPEN_SETTINGS,
            ),
            ServerLifecycleError.NoLanNetwork to expected(
                FailureCode.NO_LAN_NETWORK,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.CONNECT_TO_LOCAL_NETWORK,
            ),
            ServerLifecycleError.AmbiguousLanNetwork to expected(
                FailureCode.AMBIGUOUS_LAN_NETWORK,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.CONNECT_TO_LOCAL_NETWORK,
            ),
            ServerLifecycleError.NetworkLost to expected(
                FailureCode.NETWORK_LOST,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.CONNECT_TO_LOCAL_NETWORK,
            ),
            ServerLifecycleError.AddressChanged to expected(
                FailureCode.ADDRESS_CHANGED,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.START_SERVER,
            ),
            ServerLifecycleError.ForegroundStartNotAllowed to expected(
                FailureCode.FOREGROUND_START_NOT_ALLOWED,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.RETRY,
            ),
            ServerLifecycleError.ServerStartFailed to expected(
                FailureCode.SERVER_START_FAILED,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.RETRY,
            ),
            ServerLifecycleError.StopTimedOut to expected(
                FailureCode.SERVER_STOP_TIMEOUT,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.RETRY,
            ),
            ServerLifecycleError.SecureCertificateUnavailable to expected(
                FailureCode.SECURE_CERTIFICATE_UNAVAILABLE,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.RESET_CERTIFICATE,
            ),
        )

        cases.forEach { (source, expected) ->
            assertEquals(expected, source.toUserFacingFailure())
        }
        assertEquals(
            FailureCode.UNKNOWN_ERROR,
            ServerLifecycleError.Unexpected("Bearer lifecycle-secret").toUserFacingFailure().code,
        )
    }

    @Test
    fun privacyAllowlistDropsSecretsPathsPayloadAndStackTraces() {
        val secrets = listOf(
            "123456",
            "bearer-secret",
            "trusted-secret",
            "private message",
            "content://private/document/42",
            "java.lang.IllegalStateException",
        )
        val context = FailureContext.fromUntrusted(
            mapOf(
                "protocolVersion" to "1",
                "operationId" to "op_123",
                "direction" to "android_to_browser",
                "sizeCategory" to "large",
                "lifecycleState" to "running",
                "failureCode" to "network_lost",
                "pairingCode" to secrets[0],
                "sessionToken" to secrets[1],
                "trustedCredential" to secrets[2],
                "content" to secrets[3],
                "path" to secrets[4],
                "stackTrace" to secrets[5],
            ),
        )
        val failure = UserFacingFailure(
            code = FailureCode.NETWORK_LOST,
            severity = FailureSeverity.RECOVERABLE,
            recoveryActions = setOf(RecoveryAction.CONNECT_TO_LOCAL_NETWORK),
            context = context,
        )

        assertEquals(
            setOf(
                "protocolVersion",
                "operationId",
                "direction",
                "sizeCategory",
                "lifecycleState",
                "failureCode",
            ),
            context.values.keys,
        )
        secrets.forEach { secret ->
            assertFalse(failure.toString().contains(secret))
        }
        assertTrue(failure.context.values.values.all { it.length <= 64 })
    }

    @Test
    fun unexpectedTechnicalCauseNeverEntersUserFacingContext() {
        val lifecycle = ServerLifecycleError.Unexpected(
            "Bearer token-abc at C:\\private\\file.txt",
        ).toUserFacingFailure()

        assertEquals(FailureCode.UNKNOWN_ERROR, lifecycle.code)
        assertTrue(lifecycle.context.values.isEmpty())
        assertFalse(lifecycle.toString().contains("token-abc"))
    }

    private fun expected(
        code: FailureCode,
        severity: FailureSeverity,
        action: RecoveryAction,
    ) = UserFacingFailure(
        code = code,
        severity = severity,
        recoveryActions = setOf(action),
    )
}
