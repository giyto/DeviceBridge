package ru.hznik.devicebridge.feature.home

import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.error.UserFacingFailure

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
    val failure: UserFacingFailure? = null,
    val commandPending: Boolean = false,
    val isPermissionExplanationVisible: Boolean = false,
    val openSettingsForPermission: Boolean = false,
    val showNotificationWarning: Boolean = false,
    val pairingCode: String? = null,
    val pairingExpiresInSeconds: Long? = null,
    val pendingBrowsers: List<PendingBrowserUiState> = emptyList(),
    val activeBrowsers: List<ActiveBrowserUiState> = emptyList(),
    val textTransferStatus: HomeTextTransferStatus = HomeTextTransferStatus.Idle,
    val activeFileTransfers: List<HomeFileTransferUiState> = emptyList(),
) {
    val canStart: Boolean
        get() = !commandPending &&
            (status == HomeServerStatus.Stopped || status == HomeServerStatus.Error)

    val canStop: Boolean
        get() = !commandPending && status == HomeServerStatus.Running

    val canSendText: Boolean
        get() = status == HomeServerStatus.Running && activeBrowsers.isNotEmpty()

    val canSendFiles: Boolean
        get() = status == HomeServerStatus.Running && activeBrowsers.isNotEmpty()
}

data class HomeFileTransferUiState(
    val id: FileTransferId,
    val displayName: String,
    val sizeBytes: Long,
    val direction: FileTransferDirection,
    val phase: FileTransferPhase,
    val bytesTransferred: Long,
) {
    val progressPercent: Int
        get() = if (sizeBytes == 0L) {
            if (phase == FileTransferPhase.COMPLETED) 100 else 0
        } else {
            ((bytesTransferred * 100.0) / sizeBytes).toInt().coerceIn(0, 100)
        }
}

data class PendingBrowserUiState(
    val id: PairingRequestId,
    val browserLabel: String,
    val sourceIpv4: String,
    val expiresInSeconds: Long,
    val actionPending: Boolean = false,
    val rememberBrowserRequested: Boolean = false,
)

data class ActiveBrowserUiState(
    val id: BrowserSessionId,
    val browserLabel: String,
    val sourceIpv4: String,
    val actionPending: Boolean = false,
)

sealed interface HomeAction {
    data object StartClicked : HomeAction
    data object StartAgainClicked : HomeAction
    data object StopClicked : HomeAction
    data object RetryPermissionClicked : HomeAction
    data object RequestPermissionClicked : HomeAction
    data object OpenSettingsClicked : HomeAction
    data class PermissionsResolved(
        val localNetworkCanAskAgain: Boolean,
    ) : HomeAction
    data object NotificationWarningDismissed : HomeAction
    data class ApproveBrowser(val requestId: PairingRequestId) : HomeAction
    data class ApproveAndRememberBrowser(val requestId: PairingRequestId) : HomeAction
    data class DenyBrowser(val requestId: PairingRequestId) : HomeAction
    data class RevokeBrowser(val sessionId: BrowserSessionId) : HomeAction
}

sealed interface HomeEffect {
    data class RequestPermissions(val permissions: List<String>) : HomeEffect
    data object OpenAppSettings : HomeEffect
}
