package ru.hznik.devicebridge.domain.model

sealed interface ServerLifecycleError {
    val isRecoverable: Boolean
        get() = true

    data object LocalNetworkPermissionDenied : ServerLifecycleError
    data object PermissionRevoked : ServerLifecycleError
    data object NoLanNetwork : ServerLifecycleError
    data object AmbiguousLanNetwork : ServerLifecycleError
    data object NetworkLost : ServerLifecycleError
    data object AddressChanged : ServerLifecycleError
    data object ForegroundStartNotAllowed : ServerLifecycleError
    data object ServerStartFailed : ServerLifecycleError
    data object StopTimedOut : ServerLifecycleError

    data class Unexpected(
        val technicalCause: String? = null,
    ) : ServerLifecycleError
}
