package ru.hznik.devicebridge.feature.file

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferPhase

@Composable
fun FileScreen(
    uiState: FileUiState,
    modifier: Modifier = Modifier,
    onAction: (FileAction) -> Unit = {},
    onBack: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 20.dp, vertical = 24.dp)),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = "Вернуться на главный экран" },
        ) { Text("← Назад") }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Файлы", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(
                "Потоковая передача внутри локальной сети с проверкой SHA-256.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        uiState.errorMessage?.let { FeedbackCard(it, true) { onAction(FileAction.DismissFeedback) } }
        uiState.successMessage?.let { FeedbackCard(it, false) { onAction(FileAction.DismissFeedback) } }

        Button(
            onClick = { onAction(FileAction.PickFiles) },
            enabled = !uiState.isSubmitting,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Добавить файлы в черновик"
            },
        ) { Text("Добавить файлы") }

        if (uiState.selection.isNotEmpty()) {
            SectionCard(
                title = "Выбрано: ${uiState.selection.size}",
                trailing = {
                    TextButton(
                        onClick = { onAction(FileAction.ClearDraft) },
                        enabled = !uiState.isSubmitting,
                        modifier = Modifier.semantics {
                            contentDescription = "Очистить выбранные файлы"
                        },
                    ) { Text("Очистить") }
                },
            ) {
                uiState.selection.forEach { item ->
                    FileMetadataRow(
                        name = item.displayName,
                        size = item.sizeBytes,
                        mime = item.mimeType,
                        enabled = !uiState.isSubmitting,
                        onRemove = { onAction(FileAction.RemoveDraftItem(item.id)) },
                    )
                }
            }
        }

        if (uiState.recipients.isNotEmpty()) {
            SectionCard("Получатель") {
                uiState.recipients.forEach { recipient ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAction(FileAction.RecipientSelected(recipient.id)) }
                            .semantics { contentDescription = "Выбрать браузер ${recipient.browserLabel}" },
                        colors = CardDefaults.cardColors(
                            containerColor = if (recipient.selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        ),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(recipient.browserLabel, fontWeight = FontWeight.SemiBold)
                            Text(recipient.sourceIpv4, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Button(
            onClick = { onAction(FileAction.ConfirmSend) },
            enabled = uiState.canConfirmSend,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Подтвердить отправку файлов"
            },
        ) { Text(if (uiState.isSubmitting) "Добавляем…" else "Подтвердить отправку") }
        if (uiState.recipientSelectionRequired) {
            Text("Выберите браузер-получатель.", color = MaterialTheme.colorScheme.error)
        }

        if (uiState.transfers.isEmpty()) {
            SectionCard("Передачи") {
                Text(
                    "Здесь появятся входящие предложения и текущий прогресс.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text("Передачи", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            uiState.transfers.forEach { item ->
                TransferCard(item, uiState.hasDefaultDestination, onAction)
            }
        }
    }
}

@Composable
private fun TransferCard(
    item: FileTransferItemUiState,
    hasDefaultDestination: Boolean,
    onAction: (FileAction) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${item.direction.label()} · ${item.phase.label()} · ${formatBytes(item.sizeBytes)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.phase in ACTIVE_PHASES) {
                LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${(item.progress * 100).roundToInt()}%")
                    Text("${formatBytes(item.speedBytesPerSecond)}/с")
                }
            }
            item.failureMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (item.awaitsApproval) {
                Button(
                    onClick = { onAction(FileAction.ApproveIncoming(item.id)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (hasDefaultDestination) {
                            "Принять в выбранную папку"
                        } else {
                            "Принять и выбрать папку"
                        },
                    )
                }
                if (hasDefaultDestination) {
                    OutlinedButton(
                        onClick = {
                            onAction(FileAction.ChangeIncomingDestination(item.id))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Выбрать другую папку")
                    }
                }
            }
            if (item.canCancel) {
                OutlinedButton(
                    onClick = { onAction(FileAction.Cancel(item.id)) },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Отменить передачу ${item.displayName}"
                    },
                ) { Text("Отменить") }
            }
            if (item.canRetry) {
                OutlinedButton(
                    onClick = { onAction(FileAction.Retry(item.id)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Повторить") }
            }
            if (item.canOpen) {
                Button(
                    onClick = { onAction(FileAction.Open(item.id)) },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Открыть файл ${item.displayName}"
                    },
                ) { Text("Открыть файл") }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun FileMetadataRow(
    name: String,
    size: Long,
    mime: String,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium)
            Text("${formatBytes(size)} · $mime", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(
            onClick = onRemove,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = "Удалить $name из выбранных"
            },
        ) { Text("Удалить") }
    }
}

@Composable
private fun FeedbackCard(message: String, error: Boolean, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss),
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) { Text(message, Modifier.padding(16.dp), fontWeight = FontWeight.Medium) }
}

private val ACTIVE_PHASES = setOf(FileTransferPhase.CONNECTING, FileTransferPhase.TRANSFERRING, FileTransferPhase.VERIFYING)

private fun FileTransferDirection.label() = if (this == FileTransferDirection.ANDROID_TO_BROWSER) "На компьютер" else "На телефон"
private fun FileTransferPhase.label(): String = when (this) {
    FileTransferPhase.QUEUED -> "В очереди"
    FileTransferPhase.CONNECTING -> "Ожидает подтверждения"
    FileTransferPhase.TRANSFERRING -> "Передаётся"
    FileTransferPhase.VERIFYING -> "Проверяется"
    FileTransferPhase.COMPLETED -> "Завершено"
    FileTransferPhase.CANCELLED -> "Отменено"
    FileTransferPhase.FAILED -> "Ошибка"
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f ГБ".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f МБ".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1f КБ".format(bytes / 1024.0)
    else -> "$bytes Б"
}
