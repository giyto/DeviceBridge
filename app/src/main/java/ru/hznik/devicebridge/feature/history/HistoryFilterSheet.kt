package ru.hznik.devicebridge.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.hznik.devicebridge.domain.history.HistoryDirection
import ru.hznik.devicebridge.domain.history.HistoryKind
import ru.hznik.devicebridge.domain.history.HistoryStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryFilterSheet(
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
