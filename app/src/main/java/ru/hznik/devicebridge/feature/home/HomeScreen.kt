package ru.hznik.devicebridge.feature.home

import android.content.ClipData
import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.core.ui.BridgeIcons
import ru.hznik.devicebridge.core.ui.ConnectionStatusCard
import ru.hznik.devicebridge.core.ui.QuickActionCard
import ru.hznik.devicebridge.core.ui.MetadataRow
import ru.hznik.devicebridge.core.ui.OperationalItem
import ru.hznik.devicebridge.core.ui.ScreenHeader
import ru.hznik.devicebridge.core.ui.SectionHeader
import ru.hznik.devicebridge.core.ui.SignalFlowIndicator
import ru.hznik.devicebridge.core.ui.StateTone
import ru.hznik.devicebridge.domain.error.RecoveryAction
import ru.hznik.devicebridge.ui.theme.BridgeSpacing
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme

@Composable
fun HomeScreen(
    uiState: ServerSessionUiState,
    modifier: Modifier = Modifier,
    onAction: (HomeAction) -> Unit = {},
    onOpenText: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val statusContent = statusContent(uiState)
    val hasConnectedBrowser = uiState.activeBrowsers.isNotEmpty()
    var connectionGuideExpanded by rememberSaveable {
        mutableStateOf(true)
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
                uptimeSeconds = uiState.uptimeSeconds,
                onCopy = {
                    coroutineScope.launch {
                        clipboard.setClipEntry(
                            ClipData.newPlainText(
                                "DeviceBridge address",
                                uiState.localAddress,
                            ).toClipEntry(),
                        )
                    }
                },
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

        LifecycleButton(uiState = uiState, onAction = onAction)

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
            text = if (uiState.activeBrowsers.isEmpty()) {
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
private fun ConnectionGuideCard(
    uiState: ServerSessionUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .semantics {
                role = Role.Button
                contentDescription = if (expanded) {
                    "Скрыть инструкцию подключения"
                } else {
                    "Показать инструкцию подключения"
                }
            },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Как подключить компьютер",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = BridgeIcons.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .size(28.dp)
                        .rotate(if (expanded) 180f else 0f),
                )
            }

            if (expanded) {
                Text("Подключите телефон и компьютер к одной доверенной Wi-Fi сети.")

                if (uiState.status != HomeServerStatus.Running) {
                    Text("Запустите сервер на телефоне.")
                } else {
                    Text(
                        text = "Откройте адрес на компьютере",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("Скопируйте актуальный адрес из блока ниже и откройте его в браузере.")
                    if (uiState.activeBrowsers.isEmpty()) {
                        uiState.pairingCode?.let { code ->
                            Text("Введите код $code и подтвердите браузер на телефоне.")
                        }
                    } else {
                        Text("Браузер подключён. Можно передавать текст и файлы.")
                    }
                }

                Text(
                    text = "Аккаунт и отдельная программа для компьютера не нужны.",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
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
            OperationalItem(
                statusLabel = "Передаётся",
                title = item.displayName,
                metadata = "${item.direction.homeLabel()} · ${item.progressPercent}%" +
                    if (item.autoAccepted) " · принят автоматически" else "",
                tone = StateTone.LOADING,
            )
        }
    }
}
private fun ru.hznik.devicebridge.domain.file.FileTransferDirection.homeLabel(): String =
    if (this == ru.hznik.devicebridge.domain.file.FileTransferDirection.BROWSER_TO_ANDROID) {
        "На телефон"
    } else {
        "На компьютер"
    }

@Composable
private fun PairingCodeCard(
    code: String,
    expiresInSeconds: Long,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Код подключения",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = code,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics {
                    contentDescription = "Код подключения $code"
                    liveRegion = LiveRegionMode.Polite
                },
            )
            Text(
                text = "Код обновится через ${formatCountdown(expiresInSeconds)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "Откройте адрес сервера на компьютере, введите этот код и подтвердите браузер здесь.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun PendingBrowsersSection(
    requests: List<PendingBrowserUiState>,
    onAction: (HomeAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Запросы на подключение",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        requests.forEach { request ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = request.browserLabel,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics {
                            contentDescription =
                                "Новый запрос на подключение: ${request.browserLabel}, ${request.sourceIpv4}"
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                    Text(
                        text = request.sourceIpv4,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "Запрос истечёт через ${formatCountdown(request.expiresInSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (request.rememberBrowserRequested) {
                        Text(
                            text = "Браузер просит сохранить доступ на этом устройстве на срок до 30 дней.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                onAction(HomeAction.DenyBrowser(request.id))
                            },
                            enabled = !request.actionPending,
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription =
                                        "Отклонить ${request.browserLabel} с адреса ${request.sourceIpv4}"
                                },
                        ) { Text("Отклонить") }
                        Button(
                            onClick = {
                                onAction(HomeAction.ApproveBrowser(request.id))
                            },
                            enabled = !request.actionPending,
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription =
                                        "Разрешить ${request.browserLabel} с адреса ${request.sourceIpv4}"
                                },
                        ) {
                            Text(if (request.rememberBrowserRequested) "Один раз" else "Разрешить")
                        }
                    }
                    if (request.rememberBrowserRequested) {
                        Button(
                            onClick = {
                                onAction(HomeAction.ApproveAndRememberBrowser(request.id))
                            },
                            enabled = !request.actionPending,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription =
                                        "Разрешить и запомнить ${request.browserLabel} с адреса ${request.sourceIpv4}"
                                },
                        ) { Text("Разрешить и запомнить") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveBrowsersSection(
    sessions: List<ActiveBrowserUiState>,
    onAction: (HomeAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Подключённые браузеры: ${sessions.size}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        sessions.forEach { session ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(session.browserLabel, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = session.sourceIpv4,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            onAction(HomeAction.RevokeBrowser(session.id))
                        },
                        enabled = !session.actionPending,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription =
                                    "Отключить ${session.browserLabel} с адреса ${session.sourceIpv4}"
                            },
                    ) { Text("Отключить") }
                }
            }
        }
    }
}

@Composable
private fun LifecycleButton(
    uiState: ServerSessionUiState,
    onAction: (HomeAction) -> Unit,
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

        else -> Button(
            onClick = {
                when {
                    uiState.status != HomeServerStatus.Error ->
                        onAction(HomeAction.StartClicked)
                    RecoveryAction.REQUEST_PERMISSION in
                        uiState.failure?.recoveryActions.orEmpty() ->
                        onAction(HomeAction.RequestPermissionClicked)
                    RecoveryAction.OPEN_SETTINGS in
                        uiState.failure?.recoveryActions.orEmpty() ->
                        onAction(HomeAction.OpenSettingsClicked)
                    else -> onAction(HomeAction.StartAgainClicked)
                }
            },
            enabled = uiState.canStart,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 15.dp),
        ) {
            Text(
                when (uiState.status) {
                    HomeServerStatus.Starting -> "Запуск…"
                    HomeServerStatus.Error -> when {
                        RecoveryAction.REQUEST_PERMISSION in
                            uiState.failure?.recoveryActions.orEmpty() ->
                            "Запросить разрешение"
                        RecoveryAction.OPEN_SETTINGS in
                            uiState.failure?.recoveryActions.orEmpty() ->
                            "Открыть настройки"
                        uiState.failure != null -> "Запустить снова"
                        else -> "Повторить запуск"
                    }
                    else -> "Запустить сервер"
                },
            )
        }
    }
}

@Composable
private fun ServerDetailsCard(
    address: String,
    uptimeSeconds: Long,
    onCopy: () -> Unit,
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
        MetadataRow(label = "Время работы", value = formatUptime(uptimeSeconds))
        FilledTonalButton(
            onClick = onCopy,
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
private fun MessageCard(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    isWarning: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isWarning) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun statusContent(uiState: ServerSessionUiState): StatusContent =
    when (uiState.status) {
        HomeServerStatus.Stopped -> StatusContent(
            "Сервер остановлен",
            "Телефон пока не принимает подключения от компьютера.",
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

internal fun formatUptime(totalSeconds: Long): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0)
    val hours = safeSeconds / 3_600
    val minutes = (safeSeconds % 3_600) / 60
    val seconds = safeSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

internal fun formatCountdown(totalSeconds: Long): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0)
    val minutes = safeSeconds / 60
    val seconds = safeSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun HomeTextTransferStatus.supportingText(): String = when (this) {
    HomeTextTransferStatus.Idle -> "Отправить заметку или ссылку"
    HomeTextTransferStatus.Active -> "Передача текста выполняется"
    HomeTextTransferStatus.Completed -> "Последняя передача доставлена"
    HomeTextTransferStatus.Failed -> "Последняя передача завершилась ошибкой"
}

@Preview(name = "Главная — светлая", showBackground = true)
@Composable
private fun HomeScreenLightPreview() {
    DeviceBridgeTheme(darkTheme = false) {
        HomeScreen(uiState = ServerSessionUiState())
    }
}

@Preview(
    name = "Главная — тёмная, сервер запущен",
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
