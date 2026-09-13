package ru.hznik.devicebridge.feature.home

import ru.hznik.devicebridge.domain.model.ServerSessionStatus

data class ServerSessionUiState(
    val status: ServerSessionStatus = ServerSessionStatus.Stopped,
    val localAddress: String? = null,
    val pairingCode: String? = null,
    val connectedBrowserCount: Int = 0,
) {
    val canSendText: Boolean
        get() = status.isRunning && connectedBrowserCount > 0

    val canSendFiles: Boolean
        get() = status.isRunning && connectedBrowserCount > 0
}
