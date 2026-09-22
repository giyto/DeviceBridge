package ru.hznik.devicebridge.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ru.hznik.devicebridge.core.ui.ScreenHeader
import ru.hznik.devicebridge.domain.history.HistoryDirection
import ru.hznik.devicebridge.domain.history.HistoryKind
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryStatus
import ru.hznik.devicebridge.ui.theme.bridgeStatusColors

private val historyDateFormatter = DateTimeFormatter
    .ofPattern("dd.MM.yyyy HH:mm")
    .withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    onAction: (HistoryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filterSheetVisible by rememberSaveable { mutableStateOf(false) }

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
            ScreenHeader(
                title = "История передач",
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HistoryFilterTrigger(
                    filter = uiState.filter,
                    onClick = { filterSheetVisible = true },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                if (uiState.records.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { onAction(HistoryAction.RequestClear) },
                        enabled = !uiState.isMutating,
                        modifier = Modifier
                            .fillMaxHeight()
                            .semantics { contentDescription = "Очистить историю" },
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        Text("Очистить")
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
                    actionLabel = "Повторить",
                    onAction = { onAction(HistoryAction.RetryLoad) },
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

    if (filterSheetVisible) {
        HistoryFilterSheet(
            filter = uiState.filter,
            disabled = uiState.isMutating,
            onToggleDirection = { onAction(HistoryAction.ToggleDirection(it)) },
            onToggleKind = { onAction(HistoryAction.ToggleKind(it)) },
            onToggleStatus = { onAction(HistoryAction.ToggleStatus(it)) },
            onReset = { onAction(HistoryAction.ResetFilters) },
            onDismiss = { filterSheetVisible = false },
        )
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
private fun HistoryFilterTrigger(
    filter: ru.hznik.devicebridge.domain.history.HistoryFilter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeCount = filter.activeCount()
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .testTag("history-filter-trigger")
            .semantics {
                contentDescription = "Открыть фильтры истории. Активно: " + activeCount
            },
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 15.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Фильтры",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (activeCount == 0) {
                    "Показаны все записи"
                } else {
                    "Выбрано условий: " + activeCount
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(999.dp),
        ) {
            Text(
                text = activeCount.toString(),
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFilterSheet(
    filter: ru.hznik.devicebridge.domain.history.HistoryFilter,
    disabled: Boolean,
    onToggleDirection: (HistoryDirection) -> Unit,
    onToggleKind: (HistoryKind) -> Unit,
    onToggleStatus: (HistoryStatus) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("history-filter-sheet")
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "Фильтры истории",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            FilterGroup(
                title = "Направление",
                entries = listOf(
                    FilterEntry("На компьютер", HistoryDirection.ANDROID_TO_BROWSER),
                    FilterEntry("На телефон", HistoryDirection.BROWSER_TO_ANDROID),
                ),
                selected = filter.directions,
                disabled = disabled,
                onToggle = onToggleDirection,
            )
            FilterGroup(
                title = "Тип данных",
                entries = listOf(
                    FilterEntry("Тексты", HistoryKind.TEXT),
                    FilterEntry("Ссылки", HistoryKind.LINK),
                    FilterEntry("Файлы", HistoryKind.FILE),
                ),
                selected = filter.kinds,
                disabled = disabled,
                onToggle = onToggleKind,
            )
            FilterGroup(
                title = "Результат",
                entries = listOf(
                    FilterEntry("Доставлено", HistoryStatus.DELIVERED),
                    FilterEntry("Завершено", HistoryStatus.COMPLETED),
                    FilterEntry("Отменено", HistoryStatus.CANCELLED),
                    FilterEntry("Ошибка", HistoryStatus.FAILED),
                ),
                selected = filter.statuses,
                disabled = disabled,
                onToggle = onToggleStatus,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onReset,
                    enabled = !disabled && filter.isActive(),
                ) {
                    Text("Сбросить")
                }
                Button(onClick = onDismiss) {
                    Text("Готово")
                }
            }
        }
    }
}

private data class FilterEntry<T>(
    val label: String,
    val value: T,
)

@Composable
private fun <T> FilterGroup(
    title: String,
    entries: List<FilterEntry<T>>,
    selected: Set<T>,
    disabled: Boolean,
    onToggle: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        entries.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history-filter-option-" + entry.label)
                    .toggleable(
                        value = entry.value in selected,
                        enabled = !disabled,
                        role = Role.Checkbox,
                        onValueChange = { onToggle(entry.value) },
                    )
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = entry.value in selected,
                    onCheckedChange = null,
                    enabled = !disabled,
                )
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
@Composable
private fun HistoryRecordCard(
    record: HistoryRecord,
    disabled: Boolean,
    onAction: (HistoryAction) -> Unit,
) {
    val statusColors = MaterialTheme.bridgeStatusColors
    val statusColor = when (record.status) {
        HistoryStatus.DELIVERED,
        HistoryStatus.COMPLETED -> statusColors.success
        HistoryStatus.CANCELLED,
        HistoryStatus.FAILED -> statusColors.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history-record-card-" + record.id.value)
            .clickable(
                enabled = !disabled,
                role = Role.Button,
                onClick = { onAction(HistoryAction.OpenDetails(record.id)) },
            )
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
                        text = record.status.label(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                    )
                }
                Text(
                    text = record.kind.label(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = record.primaryLabel(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            record.file?.let { file ->
                Text(
                    text = file.sizeBytes.toReadableBytes(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = record.direction.label(),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = record.browserLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = historyDateFormatter.format(
                            Instant.ofEpochMilli(record.timestampEpochMillis),
                        ),
                        modifier = Modifier.testTag("history-record-time-" + record.id.value),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { onAction(HistoryAction.RequestDelete(record.id)) },
                    enabled = !disabled,
                ) {
                    Text("Удалить")
                }
            }
        }
    }
}
@Composable
private fun HistoryMessageCard(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
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
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
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

private fun ru.hznik.devicebridge.domain.history.HistoryFilter.activeCount(): Int =
    directions.size + kinds.size + statuses.size
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
