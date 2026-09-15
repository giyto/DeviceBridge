package ru.hznik.devicebridge.feature.home

enum class HomeServerStatus {
    Stopped,
    Starting,
    Running,
    Stopping,
    Error,
}

data class ServerSessionUiState(
    val status: HomeServerStatus = HomeServerStatus.Stopped,
    val localAddress: String? = null,
    val uptimeSeconds: Long = 0,
    val errorMessage: String? = null,
    val commandPending: Boolean = false,
    val isPermissionExplanationVisible: Boolean = false,
    val openSettingsForPermission: Boolean = false,
    val showNotificationWarning: Boolean = false,
) {
    val canStart: Boolean
        get() = !commandPending &&
            (status == HomeServerStatus.Stopped || status == HomeServerStatus.Error)

    val canStop: Boolean
        get() = !commandPending && status == HomeServerStatus.Running

    // Pairing belongs to the next change, so transfer remains honestly unavailable.
    val canSendText: Boolean = false
    val canSendFiles: Boolean = false
}

sealed interface HomeAction {
    data object StartClicked : HomeAction
    data object StopClicked : HomeAction
    data object RetryPermissionClicked : HomeAction
    data class PermissionsResolved(
        val localNetworkCanAskAgain: Boolean,
    ) : HomeAction
    data object NotificationWarningDismissed : HomeAction
}

sealed interface HomeEffect {
    data class RequestPermissions(val permissions: List<String>) : HomeEffect
    data object OpenAppSettings : HomeEffect
}
