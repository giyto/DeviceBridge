package ru.hznik.devicebridge.feature.home

import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
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
    /** The browser switches from [localAddress] to HTTPS by itself. */
    val secureMode: Boolean = false,
    val localNameNotice: LocalNameNotice? = null,
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
    /** Minutes of inactivity after which the server stopped itself, when that was the reason. */
    val idleStoppedAfterMinutes: Int? = null,
) {
    val canStart: Boolean
        get() = !commandPending &&
            (status == HomeServerStatus.Stopped || status == HomeServerStatus.Error)

    val canStop: Boolean
        get() = !commandPending && status == HomeServerStatus.Running

    /** Browsers whose tab holds a live connection now; offline sessions are listed but not counted. */
    val connectedBrowserCount: Int
        get() = activeBrowsers.count { it.connected }

    val hasConnectedBrowser: Boolean
        get() = activeBrowsers.any { it.connected }

    val canSendText: Boolean
        get() = status == HomeServerStatus.Running && hasConnectedBrowser

    val canSendFiles: Boolean
        get() = status == HomeServerStatus.Running && hasConnectedBrowser
}

data class HomeFileTransferUiState(
    val displayName: String,
    val sizeBytes: Long,
    val direction: FileTransferDirection,
    val phase: FileTransferPhase,
    val bytesTransferred: Long,
    val autoAccepted: Boolean = false,
    /** The upload continues after this many bytes kept by an earlier attempt. */
    val resumedFromBytes: Long = 0,
    val mimeType: String = "application/octet-stream",
    val speedBytesPerSecond: Long = 0,
    val senderLabel: String? = null,
)

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
    /** False when the session survives but its tab has no live connection («Не в сети»). */
    val connected: Boolean = true,
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
