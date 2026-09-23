package ru.hznik.devicebridge.server

import javax.inject.Inject
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.file.FileTransferDirection

data class ServerNotificationModel(
    val title: String,
    val text: String,
    val ongoing: Boolean,
    val showStopAction: Boolean,
)

data class FileNotificationProgress(
    val displayName: String,
    val direction: FileTransferDirection,
    val bytesTransferred: Long,
    val totalBytes: Long,
)

class ServerNotificationModelFactory @Inject constructor() {

    fun create(
        state: ServerLifecycleState,
        activeSessionCount: Int = 0,
        hasActiveTextTransfer: Boolean = false,
        activeFileTransfer: FileNotificationProgress? = null,
        idleStopAtLocalTime: String? = null,
    ): ServerNotificationModel? {
        require(activeSessionCount >= 0) { "Active session count cannot be negative" }
        return when (state) {
        ServerLifecycleState.Stopped,
        is ServerLifecycleState.Error,
        -> null

        is ServerLifecycleState.Starting -> ServerNotificationModel(
            title = "Сервер запускается",
            text = "Подготовка локального подключения",
            ongoing = true,
            showStopAction = true,
        )

        is ServerLifecycleState.Running -> ServerNotificationModel(
            title = "Сервер запущен",
            text = state.endpoint.url +
                " • $activeSessionCount браузеров • " +
                if (activeFileTransfer != null) {
                    activeFileTransfer.notificationText()
                } else if (hasActiveTextTransfer) {
                    "Передача текста выполняется"
                } else {
                    "Передача не выполняется"
                } +
                idleStopAtLocalTime?.let { "\nОстановится в $it без подключений" }.orEmpty(),
            ongoing = true,
            showStopAction = true,
        )

        is ServerLifecycleState.Stopping -> ServerNotificationModel(
            title = "Сервер останавливается",
            text = "Закрываем локальное подключение",
            ongoing = true,
            showStopAction = false,
        )
    }
    }

    private fun FileNotificationProgress.notificationText(): String {
        val directionLabel = if (direction == FileTransferDirection.BROWSER_TO_ANDROID) {
            "На телефон"
        } else {
            "На компьютер"
        }
        val progress = if (totalBytes == 0L) 0 else {
            ((bytesTransferred.coerceAtLeast(0) * 100.0) / totalBytes.coerceAtLeast(1))
                .toInt()
                .coerceIn(0, 100)
        }
        return "$directionLabel: ${displayName.safeNotificationName()} — $progress%"
    }

    private fun String.safeNotificationName(): String {
        val leaf = substringAfterLast('/').substringAfterLast('\\')
        val safe = leaf.filterNot { char ->
            char.isISOControl() || char in BIDI_CONTROL_CHARACTERS
        }.trim().take(64)
        return safe.ifBlank { "Файл" }
    }

    private companion object {
        val BIDI_CONTROL_CHARACTERS = setOf(
            '\u061c', '\u200e', '\u200f',
            '\u202a', '\u202b', '\u202c', '\u202d', '\u202e',
            '\u2066', '\u2067', '\u2068', '\u2069',
        )
    }
}
