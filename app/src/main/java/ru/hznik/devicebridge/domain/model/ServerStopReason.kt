package ru.hznik.devicebridge.domain.model

sealed interface ServerStopReason {
    data object UserRequested : ServerStopReason
    data object PermissionRevoked : ServerStopReason
    data object NetworkLost : ServerStopReason
    data object AddressChanged : ServerStopReason
    data object ProcessTerminated : ServerStopReason
}
