package ru.hznik.devicebridge.domain.model

sealed interface ServerLifecycleError {
    data object LocalNetworkPermissionDenied : ServerLifecycleError
    data object PermissionRevoked : ServerLifecycleError
    data object NoLanNetwork : ServerLifecycleError
    data object AmbiguousLanNetwork : ServerLifecycleError
    data object NetworkLost : ServerLifecycleError
    data object AddressChanged : ServerLifecycleError
    data object ForegroundStartNotAllowed : ServerLifecycleError
    data object ServerStartFailed : ServerLifecycleError
    data object StopTimedOut : ServerLifecycleError

    /** Secure mode is on but the phone's certificate or its key cannot be used. */
    data object SecureCertificateUnavailable : ServerLifecycleError

    data class Unexpected(
        val technicalCause: String? = null,
    ) : ServerLifecycleError
}
