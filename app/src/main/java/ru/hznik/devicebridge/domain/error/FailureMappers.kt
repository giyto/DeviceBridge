package ru.hznik.devicebridge.domain.error

import ru.hznik.devicebridge.domain.file.FileMetadataError
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.history.HistoryPersistenceEvent
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.session.BrowserSessionError
import ru.hznik.devicebridge.domain.settings.SettingsValidationError
import ru.hznik.devicebridge.domain.text.TextTransferFailureReason
import ru.hznik.devicebridge.domain.text.TextTransferRejection

fun ServerLifecycleError.toUserFacingFailure(): UserFacingFailure = when (this) {
    ServerLifecycleError.LocalNetworkPermissionDenied -> failure(
        FailureCode.LOCAL_NETWORK_PERMISSION_DENIED,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.REQUEST_PERMISSION,
    )
    ServerLifecycleError.PermissionRevoked -> failure(
        FailureCode.LOCAL_NETWORK_PERMISSION_REVOKED,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.OPEN_SETTINGS,
    )
    ServerLifecycleError.NoLanNetwork -> failure(
        FailureCode.NO_LAN_NETWORK,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.CONNECT_TO_LOCAL_NETWORK,
    )
    ServerLifecycleError.AmbiguousLanNetwork -> failure(
        FailureCode.AMBIGUOUS_LAN_NETWORK,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.CONNECT_TO_LOCAL_NETWORK,
    )
    ServerLifecycleError.NetworkLost -> failure(
        FailureCode.NETWORK_LOST,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.CONNECT_TO_LOCAL_NETWORK,
    )
    ServerLifecycleError.AddressChanged -> failure(
        FailureCode.ADDRESS_CHANGED,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.START_SERVER,
    )
    ServerLifecycleError.ForegroundStartNotAllowed -> failure(
        FailureCode.FOREGROUND_START_NOT_ALLOWED,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.RETRY,
    )
    ServerLifecycleError.ServerStartFailed -> failure(
        FailureCode.SERVER_START_FAILED,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.RETRY,
    )
    ServerLifecycleError.StopTimedOut -> failure(
        FailureCode.SERVER_STOP_TIMEOUT,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.RETRY,
    )
    is ServerLifecycleError.Unexpected -> unknownFailure()
}

fun BrowserSessionError.toUserFacingFailure(): UserFacingFailure = when (this) {
    BrowserSessionError.CapacityReached -> failure(
        FailureCode.SESSION_CAPACITY_REACHED,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.MANAGE_SESSIONS,
    )
    BrowserSessionError.GenerationClosed -> failure(
        FailureCode.SERVER_GENERATION_CLOSED,
        FailureSeverity.TERMINAL,
        RecoveryAction.START_SERVER,
    )
    BrowserSessionError.RequestExpired -> failure(
        FailureCode.PAIRING_REQUEST_EXPIRED,
        FailureSeverity.TERMINAL,
        RecoveryAction.PAIR_AGAIN,
    )
    BrowserSessionError.RequestNotFound -> failure(
        FailureCode.PAIRING_REQUEST_NOT_FOUND,
        FailureSeverity.TERMINAL,
        RecoveryAction.PAIR_AGAIN,
    )
    is BrowserSessionError.Unexpected -> unknownFailure()
}

fun TextTransferFailureReason.toUserFacingFailure(): UserFacingFailure = when (this) {
    TextTransferFailureReason.CONNECTION_LOST -> failure(
        FailureCode.TEXT_CONNECTION_LOST,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.RETRY,
    )
    TextTransferFailureReason.SESSION_CLOSED -> failure(
        FailureCode.SESSION_CLOSED,
        FailureSeverity.TERMINAL,
        RecoveryAction.SELECT_SESSION,
    )
    TextTransferFailureReason.PROTOCOL_ERROR -> failure(
        FailureCode.PROTOCOL_VERSION_UNSUPPORTED,
        FailureSeverity.TERMINAL,
        RecoveryAction.UPDATE_CLIENT,
    )
    TextTransferFailureReason.UNKNOWN -> unknownFailure(
        severity = FailureSeverity.TERMINAL,
        action = RecoveryAction.START_NEW_OPERATION,
    )
}

fun TextTransferRejection.toUserFacingFailure(): UserFacingFailure = when (this) {
    TextTransferRejection.EMPTY_CONTENT -> failure(
        FailureCode.EMPTY_CONTENT,
        FailureSeverity.TERMINAL,
        RecoveryAction.EDIT_CONTENT,
    )
    TextTransferRejection.CONTENT_TOO_LARGE -> failure(
        FailureCode.CONTENT_TOO_LARGE,
        FailureSeverity.TERMINAL,
        RecoveryAction.EDIT_CONTENT,
    )
    TextTransferRejection.SESSION_UNAVAILABLE -> failure(
        FailureCode.SESSION_CLOSED,
        FailureSeverity.TERMINAL,
        RecoveryAction.SELECT_SESSION,
    )
    TextTransferRejection.GENERATION_CLOSED -> failure(
        FailureCode.SERVER_GENERATION_CLOSED,
        FailureSeverity.TERMINAL,
        RecoveryAction.START_SERVER,
    )
    TextTransferRejection.MESSAGE_CONFLICT -> failure(
        FailureCode.MESSAGE_CONFLICT,
        FailureSeverity.TERMINAL,
        RecoveryAction.START_NEW_OPERATION,
    )
    TextTransferRejection.MESSAGE_NOT_FOUND -> failure(
        FailureCode.MESSAGE_NOT_FOUND,
        FailureSeverity.TERMINAL,
        RecoveryAction.START_NEW_OPERATION,
    )
}

fun FileTransferFailure.toUserFacingFailure(): UserFacingFailure = when (this) {
    FileTransferFailure.ChecksumMismatch -> failure(
        FailureCode.FILE_CHECKSUM_MISMATCH,
        FailureSeverity.TERMINAL,
        RecoveryAction.SELECT_FILE,
    )
    FileTransferFailure.StreamFailed -> failure(
        FailureCode.FILE_STREAM_FAILED,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.RETRY,
    )
    FileTransferFailure.SessionUnavailable -> failure(
        FailureCode.SESSION_CLOSED,
        FailureSeverity.TERMINAL,
        RecoveryAction.SELECT_SESSION,
    )
    FileTransferFailure.StorageUnavailable -> failure(
        FailureCode.FILE_STORAGE_UNAVAILABLE,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.SELECT_DESTINATION,
    )
    FileTransferFailure.InsufficientSpace -> failure(
        FailureCode.FILE_INSUFFICIENT_SPACE,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.SELECT_DESTINATION,
    )
    FileTransferFailure.CapacityReached -> failure(
        FailureCode.FILE_CAPACITY_REACHED,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.REDUCE_SELECTION,
    )
    FileTransferFailure.FileLimitExceeded -> failure(
        FailureCode.FILE_TOO_LARGE,
        FailureSeverity.TERMINAL,
        RecoveryAction.SELECT_FILE,
    )
    FileTransferFailure.SourceUnavailable -> failure(
        FailureCode.FILE_SOURCE_UNAVAILABLE,
        FailureSeverity.TERMINAL,
        RecoveryAction.SELECT_FILE,
    )
    FileTransferFailure.ProtocolMismatch -> failure(
        FailureCode.PROTOCOL_VERSION_UNSUPPORTED,
        FailureSeverity.TERMINAL,
        RecoveryAction.UPDATE_CLIENT,
    )
}

fun FileMetadataError.toUserFacingFailure(): UserFacingFailure = when (this) {
    FileMetadataError.INVALID_TRANSFER_ID -> failure(
        FailureCode.INVALID_TRANSFER_ID,
        FailureSeverity.TERMINAL,
        RecoveryAction.START_NEW_OPERATION,
    )
    FileMetadataError.INVALID_DISPLAY_NAME -> failure(
        FailureCode.INVALID_FILE_NAME,
        FailureSeverity.TERMINAL,
        RecoveryAction.SELECT_FILE,
    )
    FileMetadataError.INVALID_SIZE -> failure(
        FailureCode.INVALID_FILE_SIZE,
        FailureSeverity.TERMINAL,
        RecoveryAction.SELECT_FILE,
    )
    FileMetadataError.FILE_TOO_LARGE -> failure(
        FailureCode.FILE_TOO_LARGE,
        FailureSeverity.TERMINAL,
        RecoveryAction.REDUCE_SELECTION,
    )
    FileMetadataError.INVALID_CHECKSUM -> failure(
        FailureCode.INVALID_CHECKSUM,
        FailureSeverity.TERMINAL,
        RecoveryAction.SELECT_FILE,
    )
    FileMetadataError.SIZE_MISMATCH -> failure(
        FailureCode.FILE_SIZE_MISMATCH,
        FailureSeverity.TERMINAL,
        RecoveryAction.SELECT_FILE,
    )
}

fun HistoryPersistenceEvent.toUserFacingFailure(): UserFacingFailure = when (this) {
    is HistoryPersistenceEvent.WriteFailed -> failure(
        FailureCode.HISTORY_WRITE_FAILED,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.RETRY,
    )
}

fun SettingsValidationError.toUserFacingFailure(): UserFacingFailure = when (this) {
    SettingsValidationError.DEVICE_NAME -> failure(
        FailureCode.INVALID_DEVICE_NAME,
        FailureSeverity.TERMINAL,
        RecoveryAction.EDIT_SETTING,
    )
    SettingsValidationError.RETENTION_DAYS -> failure(
        FailureCode.INVALID_RETENTION_DAYS,
        FailureSeverity.TERMINAL,
        RecoveryAction.EDIT_SETTING,
    )
    SettingsValidationError.FILE_LIMIT -> failure(
        FailureCode.INVALID_FILE_LIMIT,
        FailureSeverity.TERMINAL,
        RecoveryAction.EDIT_SETTING,
    )
    SettingsValidationError.AUTO_ACCEPT_DESTINATION -> failure(
        FailureCode.FILE_STORAGE_UNAVAILABLE,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.SELECT_DESTINATION,
    )
    SettingsValidationError.IDLE_STOP_TIMEOUT -> failure(
        FailureCode.INVALID_PAYLOAD,
        FailureSeverity.TERMINAL,
        RecoveryAction.EDIT_SETTING,
    )
}

private fun failure(
    code: FailureCode,
    severity: FailureSeverity,
    action: RecoveryAction,
): UserFacingFailure = UserFacingFailure(
    code = code,
    severity = severity,
    recoveryActions = setOf(action),
)

private fun unknownFailure(
    severity: FailureSeverity = FailureSeverity.RECOVERABLE,
    action: RecoveryAction = RecoveryAction.RETRY,
): UserFacingFailure = failure(
    code = FailureCode.UNKNOWN_ERROR,
    severity = severity,
    action = action,
)
