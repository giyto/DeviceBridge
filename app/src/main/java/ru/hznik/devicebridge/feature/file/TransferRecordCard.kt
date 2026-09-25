package ru.hznik.devicebridge.feature.file

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import ru.hznik.devicebridge.core.ui.RecordCard
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.ui.theme.bridgeStatusColors

/**
 * A file transfer laid out like a history record: status and type, name and size, the current
 * progress, then direction and sender, and the actions that apply.
 */
@Composable
internal fun TransferRecordCard(
    displayName: String,
    sizeBytes: Long,
    mimeType: String,
    direction: FileTransferDirection,
    phase: FileTransferPhase,
    modifier: Modifier = Modifier,
    details: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val typeLabel = mimeType.fileTypeLabel()
    RecordCard(
        statusLabel = phase.label(direction),
        statusColor = phase.statusColor(),
        typeLabel = typeLabel,
        typeDescription = "Тип файла $typeLabel",
        title = displayName,
        subtitle = formatBytes(sizeBytes),
        directionLabel = direction.label(),
        modifier = modifier,
        details = details,
        footer = footer,
        actions = actions,
    )
}

/** Progress of a running transfer: bar, percent and speed, and where a resumed one continued. */
@Composable
internal fun TransferProgress(
    phase: FileTransferPhase,
    sizeBytes: Long,
    bytesTransferred: Long,
    speedBytesPerSecond: Long,
    resumedFromBytes: Long,
) {
    val progress = determinateProgress(phase, sizeBytes, bytesTransferred)
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${progressPercent(progress)}%")
            Text("${formatBytes(speedBytesPerSecond)}/с")
        }
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(phase.label(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (resumedFromBytes > 0) {
        Text(
            text = "Продолжение с ${formatBytes(resumedFromBytes)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The share of the file already moved, or null while the bar cannot show real progress:
 * only a transfer that is moving bytes of a known size has a determinate bar.
 */
internal fun determinateProgress(
    phase: FileTransferPhase,
    sizeBytes: Long,
    bytesTransferred: Long,
): Float? = if (phase == FileTransferPhase.TRANSFERRING && sizeBytes > 0) {
    (bytesTransferred.toDouble() / sizeBytes).toFloat().coerceIn(0f, 1f)
} else {
    null
}

internal fun progressPercent(progress: Float): Int = (progress * 100).roundToInt()

/** Who sent the file and whether it was accepted without asking; nothing when neither is known. */
@Composable
internal fun TransferSenderLine(
    autoAccepted: Boolean,
    senderLabel: String?,
    modifier: Modifier = Modifier,
) {
    val text = when {
        autoAccepted && senderLabel != null -> "Принят автоматически от $senderLabel"
        autoAccepted -> "Принят автоматически"
        else -> senderLabel ?: return
    }
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = if (autoAccepted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun FileTransferPhase.statusColor(): Color {
    val colors = MaterialTheme.bridgeStatusColors
    return when (this) {
        FileTransferPhase.COMPLETED -> colors.success
        FileTransferPhase.CANCELLED,
        FileTransferPhase.FAILED -> colors.error
        FileTransferPhase.QUEUED,
        FileTransferPhase.CONNECTING,
        FileTransferPhase.TRANSFERRING,
        FileTransferPhase.VERIFYING -> colors.info
    }
}

internal fun FileTransferDirection.label(): String =
    if (this == FileTransferDirection.ANDROID_TO_BROWSER) "На компьютер" else "На телефон"

internal fun String.fileTypeLabel(): String = when {
    equals("application/pdf", ignoreCase = true) -> "PDF"
    startsWith("image/", ignoreCase = true) -> "Фото"
    startsWith("video/", ignoreCase = true) -> "Видео"
    startsWith("audio/", ignoreCase = true) -> "Аудио"
    startsWith("text/", ignoreCase = true) -> "Текст"
    else -> "Файл"
}

/** A phone offer waits for the browser's download click, not for an approval. */
internal fun FileTransferPhase.label(direction: FileTransferDirection? = null): String = when (this) {
    FileTransferPhase.QUEUED -> "В очереди"
    FileTransferPhase.CONNECTING ->
        if (direction == FileTransferDirection.ANDROID_TO_BROWSER) "Ожидает скачивания" else "Ожидает подтверждения"
    FileTransferPhase.TRANSFERRING -> "Передаётся"
    FileTransferPhase.VERIFYING -> "Проверяется"
    FileTransferPhase.COMPLETED -> "Завершено"
    FileTransferPhase.CANCELLED -> "Отменено"
    FileTransferPhase.FAILED -> "Ошибка"
}
