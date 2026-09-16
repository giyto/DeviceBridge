package ru.hznik.devicebridge.feature.home

import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.PairingRequestId

enum class HomeServerStatus {
    Stopped,
    Starting,
    Running,
    Stopping,
    Error,
}

enum class HomeTextTransferStatus {
    Idle,
    Active,
    Completed,
    Failed,
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
    val pairingCode: String? = null,
    val pairingExpiresInSeconds: Long? = null,
    val pendingBrowsers: List<PendingBrowserUiState> = emptyList(),
    val activeBrowsers: List<ActiveBrowserUiState> = emptyList(),
    val textTransferStatus: HomeTextTransferStatus = HomeTextTransferStatus.Idle,
) {
    val canStart: Boolean
        get() = !commandPending &&
            (status == HomeServerStatus.Stopped || status == HomeServerStatus.Error)

    val canStop: Boolean
        get() = !commandPending && status == HomeServerStatus.Running

    val canSendText: Boolean
        get() = status == HomeServerStatus.Running && activeBrowsers.isNotEmpty()

    val canSendFiles: Boolean = false
}

data class PendingBrowserUiState(
    val id: PairingRequestId,
    val browserLabel: String,
    val sourceIpv4: String,
    val expiresInSeconds: Long,
    val actionPending: Boolean = false,
)

data class ActiveBrowserUiState(
    val id: BrowserSessionId,
    val browserLabel: String,
    val sourceIpv4: String,
    val actionPending: Boolean = false,
)

sealed interface HomeAction {
    data object StartClicked : HomeAction
    data object StopClicked : HomeAction
    data object RetryPermissionClicked : HomeAction
    data class PermissionsResolved(
        val localNetworkCanAskAgain: Boolean,
    ) : HomeAction
    data object NotificationWarningDismissed : HomeAction
    data class ApproveBrowser(val requestId: PairingRequestId) : HomeAction
    data class DenyBrowser(val requestId: PairingRequestId) : HomeAction
    data class RevokeBrowser(val sessionId: BrowserSessionId) : HomeAction
}

sealed interface HomeEffect {
    data class RequestPermissions(val permissions: List<String>) : HomeEffect
    data object OpenAppSettings : HomeEffect
}
