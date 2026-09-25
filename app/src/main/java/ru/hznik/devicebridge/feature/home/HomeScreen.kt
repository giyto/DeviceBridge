package ru.hznik.devicebridge.feature.home

import android.content.ClipData
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.core.ui.BridgeIcons
import ru.hznik.devicebridge.core.ui.ConnectionStatusCard
import ru.hznik.devicebridge.core.ui.QuickActionCard
import ru.hznik.devicebridge.core.ui.MetadataRow
import ru.hznik.devicebridge.core.ui.ScreenHeader
import ru.hznik.devicebridge.core.ui.SectionHeader
import ru.hznik.devicebridge.core.ui.SignalFlowIndicator
import ru.hznik.devicebridge.domain.error.RecoveryAction
import ru.hznik.devicebridge.ui.theme.BridgeSpacing
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme
import ru.hznik.devicebridge.feature.file.TransferRecordCard
import ru.hznik.devicebridge.feature.file.TransferProgress
import ru.hznik.devicebridge.feature.file.TransferSenderLine

@Composable
fun HomeScreen(
    uiState: ServerSessionUiState,
    modifier: Modifier = Modifier,
    onAction: (HomeAction) -> Unit = {},
    onOpenText: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val statusContent = statusContent(uiState)
    val hasConnectedBrowser = uiState.hasConnectedBrowser
    // Collapsed until asked for: the pairing code has its own card once the server runs.
    var connectionGuideExpanded by rememberSaveable {
        mutableStateOf(false)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 20.dp, vertical = 24.dp)),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenHeader(
            title = "DeviceBridge",
        )

        ConnectionStatusCard(
            statusLabel = statusContent.title,
            supportingText = statusContent.description,
            statusColor = statusContent.color,
            statusContainerColor = statusContent.containerColor,
        )

        SignalFlowIndicator(
            active = uiState.status == HomeServerStatus.Starting ||
                (uiState.status == HomeServerStatus.Running && hasConnectedBrowser),
            statusLabel = if (hasConnectedBrowser) {
                "Локальная связь с браузером активна"
            } else {
                "Ожидание локального подключения"
            },
        )

        ConnectionGuideCard(
            uiState = uiState,
            expanded = connectionGuideExpanded,
            onToggle = { connectionGuideExpanded = !connectionGuideExpanded },
        )

        if (
            uiState.status == HomeServerStatus.Running &&
            uiState.localAddress != null
        ) {
            ServerDetailsCard(
                address = uiState.localAddress,
                notice = uiState.localNameNotice,
                secureMode = uiState.secureMode,
                uptimeSeconds = uiState.uptimeSeconds,
                onCopy = { address ->
                    coroutineScope.launch {
                        clipboard.setClipEntry(
                            ClipData.newPlainText("DeviceBridge address", address).toClipEntry(),
                        )
                    }
                },
                onOpenSettings = onOpenSettings,
            )
        }

        if (
            uiState.status == HomeServerStatus.Running &&
            uiState.pairingCode != null &&
            uiState.pairingExpiresInSeconds != null
        ) {
            PairingCodeCard(
                code = uiState.pairingCode,
                expiresInSeconds = uiState.pairingExpiresInSeconds,
            )
        }

        if (
            uiState.status == HomeServerStatus.Running &&
            uiState.pendingBrowsers.isNotEmpty()
        ) {
            PendingBrowsersSection(
                requests = uiState.pendingBrowsers,
                onAction = onAction,
            )
        }

        if (
            uiState.status == HomeServerStatus.Running &&
            uiState.activeBrowsers.isNotEmpty()
        ) {
            ActiveBrowsersSection(
                sessions = uiState.activeBrowsers,
                connectedCount = uiState.connectedBrowserCount,
                onAction = onAction,
            )
        }

        if (uiState.activeFileTransfers.isNotEmpty()) {
            ActiveFileTransfersSection(uiState.activeFileTransfers)
        }

        if (uiState.isPermissionExplanationVisible) {
            MessageCard(
                title = "Разрешите доступ к локальной сети",
                message = if (uiState.openSettingsForPermission) {
                    "Без этого разрешения компьютер не увидит телефон. Включите его в настройках приложения."
                } else {
                    "DeviceBridge работает только внутри вашей Wi-Fi сети. Разрешение нужно для локального подключения."
                },
                actionLabel = if (uiState.openSettingsForPermission) {
                    "Открыть настройки"
                } else {
                    "Повторить запрос"
                },
                onAction = { onAction(HomeAction.RetryPermissionClicked) },
                isWarning = false,
            )
        }

        if (uiState.showNotificationWarning) {
            MessageCard(
                title = "Уведомления отключены",
                message = "Сервер может работать, но Android не покажет его состояние в шторке.",
                actionLabel = "Понятно",
                onAction = { onAction(HomeAction.NotificationWarningDismissed) },
                isWarning = true,
            )
        }

        LifecycleButton(uiState = uiState, onAction = onAction, onOpenSettings = onOpenSettings)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(
                title = "Быстрые действия",
                supportingText = "Продолжайте в уже подтверждённом браузере.",
            )
            QuickActionCard(
                icon = BridgeIcons.Text,
                title = "Текст",
                supportingText = uiState.textTransferStatus.supportingText(),
                enabled = uiState.canSendText,
                onClick = onOpenText,
            )
            QuickActionCard(
                icon = BridgeIcons.Folder,
                title = "Файлы",
                supportingText = "Передать документ или изображение",
                enabled = uiState.canSendFiles,
                onClick = onOpenFiles,
            )
        }

        Text(
            text = if (!uiState.hasConnectedBrowser) {
                "Сначала безопасно подключите браузер по коду выше."
            } else {
                "Браузер подключён. Передача текста и ссылок доступна."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActiveFileTransfersSection(items: List<HomeFileTransferUiState>) {
    Column(verticalArrangement = Arrangement.spacedBy(BridgeSpacing.small)) {
        SectionHeader(
            title = "Активные передачи",
            supportingText = "Текущий прогресс без скрытых фоновых операций.",
        )
        items.forEach { item ->
            // The same card as on the files screen, without its actions.
            TransferRecordCard(
                displayName = item.displayName,
                sizeBytes = item.sizeBytes,
                mimeType = item.mimeType,
                direction = item.direction,
                phase = item.phase,
                details = {
                    TransferProgress(
                        phase = item.phase,
                        sizeBytes = item.sizeBytes,
                        bytesTransferred = item.bytesTransferred,
                        speedBytesPerSecond = item.speedBytesPerSecond,
                        resumedFromBytes = item.resumedFromBytes,
                    )
                },
                footer = {
                    TransferSenderLine(
                        autoAccepted = item.autoAccepted,
                        senderLabel = item.senderLabel,
                    )
                },
            )
        }
    }
}

@Composable
private fun LifecycleButton(
    uiState: ServerSessionUiState,
    onAction: (HomeAction) -> Unit,
    onOpenSettings: () -> Unit,
) {
    when (uiState.status) {
        HomeServerStatus.Running -> OutlinedButton(
            onClick = { onAction(HomeAction.StopClicked) },
            enabled = uiState.canStop,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 15.dp),
        ) { Text("Остановить сервер") }

        HomeServerStatus.Stopping -> OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 15.dp),
        ) { Text("Остановка…") }

        else -> {
            val (label, onClick) = startButtonContent(uiState, onAction, onOpenSettings)
            Button(
                onClick = onClick,
                enabled = uiState.canStart,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 15.dp),
            ) {
                Text(label)
            }
        }
    }
}

/** The start button's label and what it does: a plain start, or the failure's first recovery. */
private fun startButtonContent(
    uiState: ServerSessionUiState,
    onAction: (HomeAction) -> Unit,
    onOpenSettings: () -> Unit,
): Pair<String, () -> Unit> {
    if (uiState.status != HomeServerStatus.Error) {
        val label = if (uiState.status == HomeServerStatus.Starting) "Запуск…" else "Запустить сервер"
        return label to { onAction(HomeAction.StartClicked) }
    }
    val recoveryActions = uiState.failure?.recoveryActions.orEmpty()
    return when {
        RecoveryAction.REQUEST_PERMISSION in recoveryActions ->
            "Запросить разрешение" to { onAction(HomeAction.RequestPermissionClicked) }
        RecoveryAction.OPEN_SETTINGS in recoveryActions ->
            "Открыть настройки" to { onAction(HomeAction.OpenSettingsClicked) }
        RecoveryAction.RESET_CERTIFICATE in recoveryActions ->
            "Открыть настройки HTTPS" to onOpenSettings
        else -> {
            val label = if (uiState.failure != null) "Запустить снова" else "Повторить запуск"
            label to { onAction(HomeAction.StartAgainClicked) }
        }
    }
}

@Composable
private fun ServerDetailsCard(
    address: String,
    notice: LocalNameNotice?,
    secureMode: Boolean,
    uptimeSeconds: Long,
    onCopy: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(BridgeSpacing.small),
    ) {
        SectionHeader(
            title = "Локальный сервер",
            supportingText = "Доступен только в текущей сети.",
        )
        MetadataRow(label = "Адрес", value = address, monospace = true)
        if (notice != null) {
            Text(
                text = notice.text,
                modifier = Modifier.testTag("local-name-notice"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (notice.opensSettings) {
                TextButton(onClick = onOpenSettings) {
                    Text("Открыть настройки")
                }
            }
        }
        if (secureMode) {
            Text(
                text = "Защищённый режим: браузер сам откроет зашифрованное соединение, " +
                    "а без сертификата покажет, как его установить.",
                modifier = Modifier.testTag("secure-mode-address-hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MetadataRow(label = "Время работы", value = formatUptime(uptimeSeconds))
        FilledTonalButton(
            onClick = { onCopy(address) },
            modifier = Modifier.semantics {
                contentDescription = "Скопировать адрес DeviceBridge"
            },
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(
                imageVector = BridgeIcons.Copy,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Копировать адрес")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun statusContent(uiState: ServerSessionUiState): StatusContent =
    when (uiState.status) {
        HomeServerStatus.Stopped -> StatusContent(
            "Сервер остановлен",
            uiState.idleStoppedAfterMinutes?.let { minutes ->
                "Остановлен автоматически: ${formatIdleMinutes(minutes)} без подключений."
            } ?: "Телефон пока не принимает подключения от компьютера.",
            MaterialTheme.colorScheme.outline,
            MaterialTheme.colorScheme.surfaceVariant,
        )
        HomeServerStatus.Starting -> StatusContent(
            "Сервер запускается",
            "Выбираем локальную сеть и открываем безопасную web-оболочку.",
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer,
        )
        HomeServerStatus.Running -> StatusContent(
            "Сервер запущен",
            "Откройте показанный адрес на компьютере в той же сети.",
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer,
        )
        HomeServerStatus.Stopping -> StatusContent(
            "Сервер останавливается",
            "Закрываем локальный адрес и освобождаем ресурсы.",
            MaterialTheme.colorScheme.outline,
            MaterialTheme.colorScheme.surfaceVariant,
        )
        HomeServerStatus.Error -> StatusContent(
            "Нужен повторный запуск",
            uiState.errorMessage ?: "Не удалось продолжить работу сервера.",
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.errorContainer,
        )
    }

private data class StatusContent(
    val title: String,
    val description: String,
    val color: androidx.compose.ui.graphics.Color,
    val containerColor: androidx.compose.ui.graphics.Color,
)

private fun HomeTextTransferStatus.supportingText(): String = when (this) {
    HomeTextTransferStatus.Idle -> "Отправить заметку или ссылку"
    HomeTextTransferStatus.Active -> "Передача текста выполняется"
    HomeTextTransferStatus.Completed -> "Последняя передача доставлена"
    HomeTextTransferStatus.Failed -> "Последняя передача завершилась ошибкой"
}

@Preview(name = "Главная - светлая", showBackground = true)
@Composable
private fun HomeScreenLightPreview() {
    DeviceBridgeTheme(darkTheme = false) {
        HomeScreen(uiState = ServerSessionUiState())
    }
}

@Preview(
    name = "Главная - тёмная, сервер запущен",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HomeScreenDarkRunningPreview() {
    DeviceBridgeTheme(darkTheme = true) {
        HomeScreen(
            uiState = ServerSessionUiState(
                status = HomeServerStatus.Running,
                localAddress = "http://192.168.1.24:49321",
                uptimeSeconds = 3_661,
            ),
        )
    }
}
