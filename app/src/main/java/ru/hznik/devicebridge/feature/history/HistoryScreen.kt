package ru.hznik.devicebridge.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ru.hznik.devicebridge.domain.history.HistoryDirection
import ru.hznik.devicebridge.domain.history.HistoryKind
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryStatus

private val historyDateFormatter = DateTimeFormatter
    .ofPattern("dd.MM.yyyy HH:mm")
    .withZone(ZoneId.systemDefault())

@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    onAction: (HistoryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("history-list")
            .padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 28.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "История передач",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Только локальные результаты. Файлы и полный текст здесь не хранятся.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            HistoryFilters(uiState, onAction)
        }

        if (uiState.records.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(
                        onClick = { onAction(HistoryAction.RequestClear) },
                        enabled = !uiState.isMutating,
                    ) {
                        Text("Очистить историю")
                    }
                }
            }
        }

        when (uiState.loadState) {
            HistoryLoadState.LOADING -> item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "Загрузка истории"
                        },
                    )
                }
            }
            HistoryLoadState.EMPTY -> item {
                HistoryMessageCard(
                    title = if (uiState.filter.isActive()) {
                        "По выбранным фильтрам ничего нет"
                    } else {
                        "История пока пуста"
                    },
                    body = if (uiState.filter.isActive()) {
                        "Измените фильтры, чтобы увидеть другие завершённые операции."
                    } else {
                        "Здесь появятся завершённые передачи текста, ссылок и файлов."
                    },
                )
            }
            HistoryLoadState.ERROR -> item {
                HistoryMessageCard(
                    title = "История временно недоступна",
                    body = uiState.errorMessage ?: "Повторите попытку позже.",
                )
            }
            HistoryLoadState.CONTENT -> items(
                items = uiState.records,
                key = { it.id.value },
            ) { record ->
                HistoryRecordCard(record, uiState.isMutating, onAction)
            }
        }

        uiState.errorMessage
            ?.takeIf { uiState.loadState != HistoryLoadState.ERROR }
            ?.let { message ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = message,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            TextButton(onClick = { onAction(HistoryAction.DismissError) }) {
                                Text("Закрыть")
                            }
                        }
                    }
                }
            }
    }

    uiState.selectedRecord?.let { record ->
        HistoryDetailsDialog(
            record = record,
            onDismiss = { onAction(HistoryAction.CloseDetails) },
            onDelete = { onAction(HistoryAction.RequestDelete(record.id)) },
        )
    }
    uiState.pendingDeleteId?.let {
        ConfirmationDialog(
            title = "Удалить запись?",
            body = "Файл или исходный текст удалены не будут.",
            confirmLabel = "Удалить",
            onConfirm = { onAction(HistoryAction.ConfirmDelete) },
            onDismiss = { onAction(HistoryAction.CancelDelete) },
        )
    }
    if (uiState.clearConfirmationVisible) {
        ConfirmationDialog(
            title = "Очистить всю историю?",
            body = "Активные передачи и пользовательские файлы останутся без изменений.",
            confirmLabel = "Очистить",
            onConfirm = { onAction(HistoryAction.ConfirmClear) },
            onDismiss = { onAction(HistoryAction.CancelClear) },
        )
    }
}

@Composable
private fun HistoryFilters(
    uiState: HistoryUiState,
    onAction: (HistoryAction) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Фильтры",
                modifier = Modifier.padding(horizontal = 18.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            FilterRow(
                entries = listOf(
                    FilterEntry("На компьютер", HistoryDirection.ANDROID_TO_BROWSER),
                    FilterEntry("На телефон", HistoryDirection.BROWSER_TO_ANDROID),
                ),
                selected = uiState.filter.directions,
                onToggle = { onAction(HistoryAction.ToggleDirection(it)) },
            )
            FilterRow(
                entries = listOf(
                    FilterEntry("Тексты", HistoryKind.TEXT),
                    FilterEntry("Ссылки", HistoryKind.LINK),
                    FilterEntry("Файлы", HistoryKind.FILE),
                ),
                selected = uiState.filter.kinds,
                onToggle = { onAction(HistoryAction.ToggleKind(it)) },
            )
            FilterRow(
                entries = listOf(
                    FilterEntry("Доставлено", HistoryStatus.DELIVERED),
                    FilterEntry("Завершено", HistoryStatus.COMPLETED),
                    FilterEntry("Отменено", HistoryStatus.CANCELLED),
                    FilterEntry("Ошибка", HistoryStatus.FAILED),
                ),
                selected = uiState.filter.statuses,
                onToggle = { onAction(HistoryAction.ToggleStatus(it)) },
            )
        }
    }
}

private data class FilterEntry<T>(
    val label: String,
    val value: T,
)

@Composable
private fun <T> FilterRow(
    entries: List<FilterEntry<T>>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { entry ->
            FilterChip(
                selected = entry.value in selected,
                onClick = { onToggle(entry.value) },
                label = { Text(entry.label) },
                modifier = Modifier.semantics {
                    contentDescription = "Фильтр " + entry.label
                },
            )
        }
    }
}

@Composable
private fun HistoryRecordCard(
    record: HistoryRecord,
    disabled: Boolean,
    onAction: (HistoryAction) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !disabled) {
                onAction(HistoryAction.OpenDetails(record.id))
            }
            .semantics {
                contentDescription = "Открыть детали " + record.primaryLabel()
            },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = record.kind.label(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = record.status.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = record.primaryLabel(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = record.direction.label() + " · " + record.browserLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = historyDateFormatter.format(
                    Instant.ofEpochMilli(record.timestampEpochMillis),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { onAction(HistoryAction.RequestDelete(record.id)) },
                enabled = !disabled,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Удалить")
            }
        }
    }
}

@Composable
private fun HistoryMessageCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryDetailsDialog(
    record: HistoryRecord,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(record.kind.label()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailLine("Результат", record.status.label())
                DetailLine("Направление", record.direction.label())
                DetailLine("Браузер", record.browserLabel)
                DetailLine(
                    "Время",
                    historyDateFormatter.format(
                        Instant.ofEpochMilli(record.timestampEpochMillis),
                    ),
                )
                record.textPreview?.let { DetailLine("Фрагмент", it) }
                record.file?.let { file ->
                    DetailLine("Имя", file.displayName)
                    DetailLine("MIME", file.mimeType)
                    DetailLine("Размер", file.sizeBytes.toReadableBytes())
                    DetailLine("SHA-256", file.sha256)
                }
                record.failureReason?.let { DetailLine("Причина", it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        dismissButton = {
            TextButton(onClick = onDelete) { Text("Удалить") }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics {
                    contentDescription = "Подтвердить: $title"
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

private fun ru.hznik.devicebridge.domain.history.HistoryFilter.isActive(): Boolean =
    directions.isNotEmpty() || kinds.isNotEmpty() || statuses.isNotEmpty()

private fun HistoryRecord.primaryLabel(): String =
    file?.displayName ?: textPreview.orEmpty().ifBlank { kind.label() }

private fun HistoryKind.label(): String = when (this) {
    HistoryKind.TEXT -> "Текст"
    HistoryKind.LINK -> "Ссылка"
    HistoryKind.FILE -> "Файл"
}

private fun HistoryDirection.label(): String = when (this) {
    HistoryDirection.ANDROID_TO_BROWSER -> "На компьютер"
    HistoryDirection.BROWSER_TO_ANDROID -> "На телефон"
}

private fun HistoryStatus.label(): String = when (this) {
    HistoryStatus.DELIVERED -> "Доставлено"
    HistoryStatus.COMPLETED -> "Завершено"
    HistoryStatus.CANCELLED -> "Отменено"
    HistoryStatus.FAILED -> "Ошибка"
}

private fun Long.toReadableBytes(): String = when {
    this >= 1024L * 1024 * 1024 -> "%.2f ГиБ".format(this / (1024.0 * 1024 * 1024))
    this >= 1024L * 1024 -> "%.2f МиБ".format(this / (1024.0 * 1024))
    this >= 1024L -> "%.2f КиБ".format(this / 1024.0)
    else -> "$this Б"
}
