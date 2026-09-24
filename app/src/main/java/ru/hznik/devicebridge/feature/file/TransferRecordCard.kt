package ru.hznik.devicebridge.feature.file

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
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
    val statusColor = phase.statusColor()
    val typeLabel = mimeType.fileTypeLabel()
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(statusColor, CircleShape),
                    )
                    Text(
                        text = phase.label(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                    )
                }
                Text(
                    text = typeLabel,
                    modifier = Modifier.semantics { contentDescription = "Тип файла $typeLabel" },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatBytes(sizeBytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            details()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = direction.label(),
                    style = MaterialTheme.typography.labelLarge,
                )
                footer()
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions()
            }
        }
    }
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
    val determinate = phase == FileTransferPhase.TRANSFERRING && sizeBytes > 0
    if (determinate) {
        val progress = (bytesTransferred.toDouble() / sizeBytes).toFloat().coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${(progress * 100).roundToInt()}%")
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

internal fun FileTransferPhase.label(): String = when (this) {
    FileTransferPhase.QUEUED -> "В очереди"
    FileTransferPhase.CONNECTING -> "Ожидает подтверждения"
    FileTransferPhase.TRANSFERRING -> "Передаётся"
    FileTransferPhase.VERIFYING -> "Проверяется"
    FileTransferPhase.COMPLETED -> "Завершено"
    FileTransferPhase.CANCELLED -> "Отменено"
    FileTransferPhase.FAILED -> "Ошибка"
}
