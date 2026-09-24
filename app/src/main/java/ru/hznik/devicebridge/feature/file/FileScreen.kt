package ru.hznik.devicebridge.feature.file

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.hznik.devicebridge.core.ui.PrimaryActionButton
import ru.hznik.devicebridge.core.ui.ScreenHeader
import ru.hznik.devicebridge.core.ui.SecondaryActionButton
import ru.hznik.devicebridge.core.ui.SectionHeader
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.ui.theme.bridgeStatusColors

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
        ScreenHeader(
            eyebrow = "Текущая сессия",
            title = "Файлы",
            supportingText = "Потоковая передача внутри локальной сети с проверкой SHA-256.",
        )

        uiState.errorMessage?.let { FeedbackCard(it, true) { onAction(FileAction.DismissFeedback) } }
        uiState.successMessage?.let { FeedbackCard(it, false) { onAction(FileAction.DismissFeedback) } }

        SecondaryActionButton(
            label = "Добавить файлы",
            onClick = { onAction(FileAction.PickFiles) },
            enabled = !uiState.isSubmitting,
            contentDescription = "Добавить файлы в черновик",
        )

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
                            .selectable(
                                selected = recipient.selected,
                                role = Role.RadioButton,
                                onClick = {
                                    onAction(FileAction.RecipientSelected(recipient.id))
                                },
                            )
                            .semantics {
                                contentDescription = "Выбрать браузер ${recipient.browserLabel}"
                            },
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

        PrimaryActionButton(
            label = if (uiState.isSubmitting) "Добавляем…" else "Подтвердить отправку",
            onClick = { onAction(FileAction.ConfirmSend) },
            enabled = uiState.canConfirmSend,
            loading = uiState.isSubmitting,
            contentDescription = "Подтвердить отправку файлов",
        )
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
            SectionHeader(
                title = "Передачи",
                supportingText = "Очередь, прогресс и доступные действия текущей сессии.",
            )
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
    TransferRecordCard(
        displayName = item.displayName,
        sizeBytes = item.sizeBytes,
        mimeType = item.mimeType,
        direction = item.direction,
        phase = item.phase,
        modifier = Modifier.semantics {
            contentDescription = "Этап передачи ${item.displayName}: ${item.phase.label(item.direction)}" +
                if (item.autoAccepted) ", принят автоматически" else ""
            liveRegion = LiveRegionMode.Polite
        },
        details = {
            if (item.autoAcceptPaused && item.awaitsApproval) {
                Text(
                    text = "Автоприём приостановлен: папка недоступна. Примите файл вручную.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (item.hasActiveProgress) {
                TransferProgress(
                    phase = item.phase,
                    sizeBytes = item.sizeBytes,
                    bytesTransferred = item.bytesTransferred,
                    speedBytesPerSecond = item.speedBytesPerSecond,
                    resumedFromBytes = item.resumedFromBytes,
                )
            }
            item.failureMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        footer = {
            if (item.autoAccepted) {
                Text(
                    text = item.senderLabel
                        ?.let { "Принят автоматически от $it" }
                        ?: "Принят автоматически",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("auto-accepted-${item.id.value}"),
                )
            } else {
                item.senderLabel?.let { sender ->
                    Text(
                        text = sender,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            if (item.awaitsApproval) {
                // A paused auto-accept means the saved folder is unavailable, so accepting opens the picker.
                val usableDefaultDestination = hasDefaultDestination && !item.autoAcceptPaused
                Button(
                    onClick = { onAction(FileAction.ApproveIncoming(item.id)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (usableDefaultDestination) {
                            "Принять в выбранную папку"
                        } else {
                            "Принять и выбрать папку"
                        },
                    )
                }
                if (usableDefaultDestination) {
                    OutlinedButton(
                        onClick = { onAction(FileAction.ChangeIncomingDestination(item.id)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Выбрать другую папку") }
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
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = if (item.continuesUpload) {
                            "Продолжить передачу ${item.displayName}"
                        } else {
                            "Повторить передачу ${item.displayName}"
                        }
                    },
                ) { Text(if (item.continuesUpload) "Продолжить" else "Повторить") }
            }
            if (item.canOpen) {
                Button(
                    onClick = { onAction(FileAction.Open(item.id)) },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Открыть файл ${item.displayName}"
                    },
                ) { Text("Открыть файл") }
            }
        },
    )
}

@Composable
private fun SectionCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader(
            title = title,
            trailing = { trailing?.invoke() },
        )
        content()
        androidx.compose.material3.HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
        )
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = "Закрыть сообщение",
                role = Role.Button,
                onClick = onDismiss,
            )
            .semantics {
                contentDescription = "${message.trim()} Закрыть сообщение"
            },
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) { Text(message, Modifier.padding(16.dp), fontWeight = FontWeight.Medium) }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f ГБ".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f МБ".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1f КБ".format(bytes / 1024.0)
    else -> "$bytes Б"
}
