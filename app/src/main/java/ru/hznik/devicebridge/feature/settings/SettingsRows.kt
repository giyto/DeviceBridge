package ru.hznik.devicebridge.feature.settings

import android.app.StatusBarManager
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import ru.hznik.devicebridge.R
import ru.hznik.devicebridge.core.text.ruPlural
import ru.hznik.devicebridge.core.ui.DestructiveActionButton
import ru.hznik.devicebridge.core.ui.TonalActionButton
import ru.hznik.devicebridge.data.file.PartialUploadSummary
import ru.hznik.devicebridge.domain.settings.IdleStopTimeout
import ru.hznik.devicebridge.feature.file.formatBytes
import ru.hznik.devicebridge.server.AndroidEventNotificationPublisher
import ru.hznik.devicebridge.server.DeviceBridgeTileService

@Composable
internal fun DarkThemeRow(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dark-theme-toggle")
            .toggleable(
                value = darkTheme,
                role = Role.Switch,
                onValueChange = onDarkThemeChange,
            )
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Тёмная тема",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Switch(
            checked = darkTheme,
            onCheckedChange = null,
        )
    }
}

@Composable
internal fun IdleStopSelector(
    selected: IdleStopTimeout,
    choices: List<IdleStopTimeout>,
    fieldState: SettingsFieldState,
    onSelect: (IdleStopTimeout) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Автоостановка без подключений",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Сервер остановится сам, если за это время к нему не подключится ни один " +
                "браузер и не будет передач.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Column(Modifier.selectableGroup()) {
            choices.forEach { choice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("idle-stop-${choice.storageValue}")
                        .selectable(
                            selected = choice == selected,
                            enabled = !fieldState.isSaving,
                            role = Role.RadioButton,
                            onClick = { onSelect(choice) },
                        )
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = choice == selected,
                        onClick = null,
                        enabled = !fieldState.isSaving,
                    )
                    Text(choice.label(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        fieldState.errorMessage?.let { FieldError(it) }
    }
}

@Composable
internal fun AddServerTileRow() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.testTag("add-server-tile"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Плитка в быстрых настройках",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            var result by remember { mutableStateOf<String?>(null) }
            Text(
                text = "Запускайте и останавливайте сервер из шторки, не открывая приложение.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            TonalActionButton(
                label = "Добавить плитку",
                onClick = { requestAddServerTile(context) { result = it } },
                contentDescription = "Добавить плитку DeviceBridge в быстрые настройки",
            )
            result?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(
                text = "Откройте шторку, нажмите «Изменить» (значок карандаша) и перетащите " +
                    "плитку DeviceBridge в список активных. Она запускает и останавливает сервер.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Uses the Android channel as the only switch, so there is no second one to disagree with it. */
@Composable
internal fun EventNotificationsRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AndroidEventNotificationPublisher.eventsEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        enabled = AndroidEventNotificationPublisher.eventsEnabled(context)
        onPauseOrDispose {}
    }
    Column(
        modifier = Modifier.testTag("event-notifications"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Уведомления о событиях",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (enabled) {
                "Включены. Когда приложение свёрнуто, телефон сообщит о запросе подключения, " +
                    "тексте и файлах с компьютера."
            } else {
                "Выключены в системе. Включите их, чтобы узнавать о запросах подключения, " +
                    "тексте и файлах с компьютера."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        TonalActionButton(
            label = if (enabled) "Настроить" else "Включить",
            onClick = {
                runCatching {
                    context.startActivity(AndroidEventNotificationPublisher.settingsIntent(context))
                }
            },
            contentDescription = "Открыть системные настройки уведомлений о событиях",
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun requestAddServerTile(context: Context, onResult: (String?) -> Unit) {
    val statusBar = context.getSystemService(StatusBarManager::class.java) ?: return
    statusBar.requestAddTileService(
        DeviceBridgeTileService.component(context),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_server_notification),
        context.mainExecutor,
    ) { code ->
        onResult(
            when (code) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Плитка добавлена."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                    "Плитка уже есть в быстрых настройках."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> null
                else -> "Не удалось добавить плитку. Добавьте её вручную через «Изменить» в шторке."
            },
        )
    }
}

private fun IdleStopTimeout.label(): String = when (this) {
    IdleStopTimeout.OFF -> "Не останавливать"
    IdleStopTimeout.MIN_15 -> "Через 15 минут"
    IdleStopTimeout.MIN_30 -> "Через 30 минут"
    IdleStopTimeout.MIN_60 -> "Через 1 час"
}

@Composable
internal fun PartialUploadsRow(
    summary: PartialUploadSummary,
    pending: Boolean,
    error: String?,
    onDiscard: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Незавершённые файлы",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (summary.count == 0) {
                "Нет. Прерванная передача файла от 8 МиБ сохраняется на 24 часа, чтобы её можно было продолжить."
            } else {
                "${partialUploadCountLabel(summary.count)}, ${formatBytes(summary.totalBytes)}. " +
                    "Хранятся 24 часа, чтобы передачу можно было продолжить."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("partial-uploads-summary"),
        )
        error?.let { FieldError(it) }
        if (summary.count > 0) {
            DestructiveActionButton(
                label = if (pending) "Удаление…" else "Удалить незавершённые",
                onClick = onDiscard,
                enabled = !pending,
                contentDescription = "Удалить незавершённые файлы",
            )
        }
    }
}

internal fun partialUploadCountLabel(count: Int): String =
    "$count ${ruPlural(count, "файл", "файла", "файлов")}"

@Composable
internal fun AutoAcceptRow(
    checked: Boolean,
    status: AutoAcceptStatus,
    fieldState: SettingsFieldState,
    onCheckedChange: (Boolean) -> Unit,
) {
    val statusText = when (status) {
        AutoAcceptStatus.NO_DESTINATION -> "Сначала выберите папку для входящих файлов."
        AutoAcceptStatus.OFF ->
            "Файлы от запомненных браузеров будут сохраняться в выбранную папку без подтверждения."
        AutoAcceptStatus.ON ->
            "Включено. Одноразовые подключения по-прежнему требуют подтверждения."
        AutoAcceptStatus.PAUSED ->
            "Приостановлено: папка недоступна. Выберите папку снова."
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSwitchRow(
            title = "Автоприём от запомненных браузеров",
            statusText = statusText,
            checked = checked,
            enabled = status != AutoAcceptStatus.NO_DESTINATION && !fieldState.isSaving,
            onCheckedChange = onCheckedChange,
            testTag = "auto-accept-toggle",
            statusColor = if (status == AutoAcceptStatus.PAUSED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        fieldState.errorMessage?.let { FieldError(it) }
    }
}

/** A titled switch whose status line is also read out as the switch's state. */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    statusText: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    statusColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics { stateDescription = statusText }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = statusText,
                color = statusColor,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

@Composable
internal fun SettingsTextField(
    title: String,
    supportingText: String? = null,
    value: String,
    onValueChange: (String) -> Unit,
    fieldState: SettingsFieldState,
    fieldDescription: String,
    saveDescription: String,
    isSaved: Boolean,
    onSave: () -> Unit,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    suffix: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = fieldDescription
            },
            singleLine = true,
            isError = fieldState.errorMessage != null,
            keyboardOptions = keyboardOptions,
            suffix = suffix?.let { text -> { Text(text) } },
        )
        (fieldState.errorMessage ?: supportingText)?.let { text ->
            Text(
                text = text,
                color = if (fieldState.errorMessage != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TonalActionButton(
            label = when {
                fieldState.isSaving -> "Сохранение…"
                isSaved -> "Сохранено"
                else -> "Сохранить"
            },
            onClick = onSave,
            enabled = !fieldState.isSaving,
            loading = fieldState.isSaving,
            contentDescription = saveDescription,
        )
    }
}

@Composable
internal fun FieldError(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}
