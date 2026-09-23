package ru.hznik.devicebridge.domain.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileMetadataError
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.history.HistoryKind
import ru.hznik.devicebridge.domain.history.HistoryPersistenceEvent
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.session.BrowserSessionError
import ru.hznik.devicebridge.domain.settings.SettingsValidationError
import ru.hznik.devicebridge.domain.text.TextTransferFailureReason
import ru.hznik.devicebridge.domain.text.TextTransferRejection

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
    fun mapsEverySessionSourceToAStableFailure() {
        val cases = listOf(
            BrowserSessionError.CapacityReached to expected(
                FailureCode.SESSION_CAPACITY_REACHED,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.MANAGE_SESSIONS,
            ),
            BrowserSessionError.GenerationClosed to expected(
                FailureCode.SERVER_GENERATION_CLOSED,
                FailureSeverity.TERMINAL,
                RecoveryAction.START_SERVER,
            ),
            BrowserSessionError.RequestExpired to expected(
                FailureCode.PAIRING_REQUEST_EXPIRED,
                FailureSeverity.TERMINAL,
                RecoveryAction.PAIR_AGAIN,
            ),
            BrowserSessionError.RequestNotFound to expected(
                FailureCode.PAIRING_REQUEST_NOT_FOUND,
                FailureSeverity.TERMINAL,
                RecoveryAction.PAIR_AGAIN,
            ),
        )

        cases.forEach { (source, expected) ->
            assertEquals(expected, source.toUserFacingFailure())
        }
        assertEquals(
            FailureCode.UNKNOWN_ERROR,
            BrowserSessionError.Unexpected("token=session-secret").toUserFacingFailure().code,
        )
    }

    @Test
    fun mapsEveryTextSourceToAStableFailure() {
        val failureCases = mapOf(
            TextTransferFailureReason.CONNECTION_LOST to expected(
                FailureCode.TEXT_CONNECTION_LOST,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.RETRY,
            ),
            TextTransferFailureReason.SESSION_CLOSED to expected(
                FailureCode.SESSION_CLOSED,
                FailureSeverity.TERMINAL,
                RecoveryAction.SELECT_SESSION,
            ),
            TextTransferFailureReason.PROTOCOL_ERROR to expected(
                FailureCode.PROTOCOL_VERSION_UNSUPPORTED,
                FailureSeverity.TERMINAL,
                RecoveryAction.UPDATE_CLIENT,
            ),
            TextTransferFailureReason.UNKNOWN to expected(
                FailureCode.UNKNOWN_ERROR,
                FailureSeverity.TERMINAL,
                RecoveryAction.START_NEW_OPERATION,
            ),
        )
        val rejectionCases = mapOf(
            TextTransferRejection.EMPTY_CONTENT to expected(
                FailureCode.EMPTY_CONTENT,
                FailureSeverity.TERMINAL,
                RecoveryAction.EDIT_CONTENT,
            ),
            TextTransferRejection.CONTENT_TOO_LARGE to expected(
                FailureCode.CONTENT_TOO_LARGE,
                FailureSeverity.TERMINAL,
                RecoveryAction.EDIT_CONTENT,
            ),
            TextTransferRejection.SESSION_UNAVAILABLE to expected(
                FailureCode.SESSION_CLOSED,
                FailureSeverity.TERMINAL,
                RecoveryAction.SELECT_SESSION,
            ),
            TextTransferRejection.GENERATION_CLOSED to expected(
                FailureCode.SERVER_GENERATION_CLOSED,
                FailureSeverity.TERMINAL,
                RecoveryAction.START_SERVER,
            ),
            TextTransferRejection.MESSAGE_CONFLICT to expected(
                FailureCode.MESSAGE_CONFLICT,
                FailureSeverity.TERMINAL,
                RecoveryAction.START_NEW_OPERATION,
            ),
            TextTransferRejection.MESSAGE_NOT_FOUND to expected(
                FailureCode.MESSAGE_NOT_FOUND,
                FailureSeverity.TERMINAL,
                RecoveryAction.START_NEW_OPERATION,
            ),
        )

        assertEquals(TextTransferFailureReason.entries.toSet(), failureCases.keys)
        assertEquals(TextTransferRejection.entries.toSet(), rejectionCases.keys)
        failureCases.forEach { (source, expected) ->
            assertEquals(expected, source.toUserFacingFailure())
        }
        rejectionCases.forEach { (source, expected) ->
            assertEquals(expected, source.toUserFacingFailure())
        }
    }

    @Test
    fun mapsEveryFileSourceToAStableFailure() {
        val transferCases = listOf(
            FileTransferFailure.ChecksumMismatch to expected(
                FailureCode.FILE_CHECKSUM_MISMATCH,
                FailureSeverity.TERMINAL,
                RecoveryAction.SELECT_FILE,
            ),
            FileTransferFailure.StreamFailed to expected(
                FailureCode.FILE_STREAM_FAILED,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.RETRY,
            ),
            FileTransferFailure.SessionUnavailable to expected(
                FailureCode.SESSION_CLOSED,
                FailureSeverity.TERMINAL,
                RecoveryAction.SELECT_SESSION,
            ),
            FileTransferFailure.StorageUnavailable to expected(
                FailureCode.FILE_STORAGE_UNAVAILABLE,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.SELECT_DESTINATION,
            ),
            FileTransferFailure.InsufficientSpace to expected(
                FailureCode.FILE_INSUFFICIENT_SPACE,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.SELECT_DESTINATION,
            ),
            FileTransferFailure.CapacityReached to expected(
                FailureCode.FILE_CAPACITY_REACHED,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.REDUCE_SELECTION,
            ),
            FileTransferFailure.FileLimitExceeded to expected(
                FailureCode.FILE_TOO_LARGE,
                FailureSeverity.TERMINAL,
                RecoveryAction.SELECT_FILE,
            ),
            FileTransferFailure.SourceUnavailable to expected(
                FailureCode.FILE_SOURCE_UNAVAILABLE,
                FailureSeverity.TERMINAL,
                RecoveryAction.SELECT_FILE,
            ),
            FileTransferFailure.ProtocolMismatch to expected(
                FailureCode.PROTOCOL_VERSION_UNSUPPORTED,
                FailureSeverity.TERMINAL,
                RecoveryAction.UPDATE_CLIENT,
            ),
        )
        val metadataCases = mapOf(
            FileMetadataError.INVALID_TRANSFER_ID to expected(
                FailureCode.INVALID_TRANSFER_ID,
                FailureSeverity.TERMINAL,
                RecoveryAction.START_NEW_OPERATION,
            ),
            FileMetadataError.INVALID_DISPLAY_NAME to expected(
                FailureCode.INVALID_FILE_NAME,
                FailureSeverity.TERMINAL,
                RecoveryAction.SELECT_FILE,
            ),
            FileMetadataError.INVALID_SIZE to expected(
                FailureCode.INVALID_FILE_SIZE,
                FailureSeverity.TERMINAL,
                RecoveryAction.SELECT_FILE,
            ),
            FileMetadataError.FILE_TOO_LARGE to expected(
                FailureCode.FILE_TOO_LARGE,
                FailureSeverity.TERMINAL,
                RecoveryAction.REDUCE_SELECTION,
            ),
            FileMetadataError.INVALID_CHECKSUM to expected(
                FailureCode.INVALID_CHECKSUM,
                FailureSeverity.TERMINAL,
                RecoveryAction.SELECT_FILE,
            ),
            FileMetadataError.SIZE_MISMATCH to expected(
                FailureCode.FILE_SIZE_MISMATCH,
                FailureSeverity.TERMINAL,
                RecoveryAction.SELECT_FILE,
            ),
        )

        assertEquals(FileMetadataError.entries.toSet(), metadataCases.keys)
        transferCases.forEach { (source, expected) ->
            assertEquals(expected, source.toUserFacingFailure())
        }
        metadataCases.forEach { (source, expected) ->
            assertEquals(expected, source.toUserFacingFailure())
        }
    }

    @Test
    fun mapsEveryPersistenceSourceToAStableFailure() {
        val settingsCases = mapOf(
            SettingsValidationError.DEVICE_NAME to expected(
                FailureCode.INVALID_DEVICE_NAME,
                FailureSeverity.TERMINAL,
                RecoveryAction.EDIT_SETTING,
            ),
            SettingsValidationError.RETENTION_DAYS to expected(
                FailureCode.INVALID_RETENTION_DAYS,
                FailureSeverity.TERMINAL,
                RecoveryAction.EDIT_SETTING,
            ),
            SettingsValidationError.FILE_LIMIT to expected(
                FailureCode.INVALID_FILE_LIMIT,
                FailureSeverity.TERMINAL,
                RecoveryAction.EDIT_SETTING,
            ),
            SettingsValidationError.AUTO_ACCEPT_DESTINATION to expected(
                FailureCode.FILE_STORAGE_UNAVAILABLE,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.SELECT_DESTINATION,
            ),
        )

        assertEquals(SettingsValidationError.entries.toSet(), settingsCases.keys)
        settingsCases.forEach { (source, expected) ->
            assertEquals(expected, source.toUserFacingFailure())
        }
        assertEquals(
            expected(
                FailureCode.HISTORY_WRITE_FAILED,
                FailureSeverity.RECOVERABLE,
                RecoveryAction.RETRY,
            ),
            HistoryPersistenceEvent.WriteFailed(HistoryKind.FILE).toUserFacingFailure(),
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
        val session = BrowserSessionError.Unexpected(
            "trustedCredential=secret-value",
        ).toUserFacingFailure()

        assertEquals(FailureCode.UNKNOWN_ERROR, lifecycle.code)
        assertEquals(FailureCode.UNKNOWN_ERROR, session.code)
        assertTrue(lifecycle.context.values.isEmpty())
        assertTrue(session.context.values.isEmpty())
        assertFalse(lifecycle.toString().contains("token-abc"))
        assertFalse(session.toString().contains("secret-value"))
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
