package ru.hznik.devicebridge.domain.error

import ru.hznik.devicebridge.domain.model.ServerLifecycleError

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
    ServerLifecycleError.SecureCertificateUnavailable -> failure(
        FailureCode.SECURE_CERTIFICATE_UNAVAILABLE,
        FailureSeverity.RECOVERABLE,
        RecoveryAction.RESET_CERTIFICATE,
    )
    is ServerLifecycleError.Unexpected -> unknownFailure()
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

private fun unknownFailure(): UserFacingFailure = failure(
    code = FailureCode.UNKNOWN_ERROR,
    severity = FailureSeverity.RECOVERABLE,
    action = RecoveryAction.RETRY,
)
