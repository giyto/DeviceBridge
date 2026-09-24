package ru.hznik.devicebridge.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.hznik.devicebridge.data.tls.RootCertificateStatus

/** The HTTPS switch, the phone's root certificate to compare on the computer, and its reset. */
@Composable
internal fun SecureModeSection(
    uiState: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
) {
    val enabled = uiState.settings.secureModeEnabled
    val saving = uiState.secureModeState.isSaving
    val statusText = when {
        saving -> "Применяем…"
        enabled -> "Включено: браузер открывает DeviceBridge по HTTPS, трафик в Wi-Fi " +
            "зашифрован. После включения браузер один раз подключается заново по коду."
        else -> "Трафик в Wi-Fi будет зашифрован. На компьютер нужно один раз установить " +
            "сертификат телефона - инструкция откроется по прежнему адресу DeviceBridge."
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("secure-mode-toggle")
                .toggleable(
                    value = enabled,
                    enabled = !saving,
                    role = Role.Switch,
                    onValueChange = { onAction(SettingsAction.SecureModeToggled(it)) },
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
                    text = "Защищённый режим (HTTPS)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = statusText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = null, enabled = !saving)
        }
        uiState.secureModeState.errorMessage?.let { FieldErrorText(it) }

        when (val root = uiState.rootCertificate) {
            RootCertificateStatus.NotCreated -> Unit
            is RootCertificateStatus.Ready -> RootCertificateDetails(root, uiState, onAction)
            RootCertificateStatus.Unusable -> {
                FieldErrorText(
                    "Сертификат телефона повреждён или его ключ недоступен. " +
                        "Сбросьте сертификат, чтобы защищённый режим снова работал.",
                )
                ResetCertificateButton(uiState, onAction)
            }
        }

        if (uiState.certificateWasReset) {
            Text(
                text = "Создан новый сертификат. На компьютерах удалите старый сертификат " +
                    "«DeviceBridge Local CA» из доверенных корневых и установите новый " +
                    "по прежнему адресу DeviceBridge.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { onAction(SettingsAction.CertificateResetNoticeDismissed) }) {
                Text("Понятно")
            }
        }
    }

    uiState.pendingSecureModeChange?.let { change ->
        SecureModeChangeDialog(
            change = change,
            onConfirm = { onAction(SettingsAction.SecureModeChangeConfirmed) },
            onDismiss = { onAction(SettingsAction.SecureModeChangeDismissed) },
        )
    }
}

@Composable
private fun RootCertificateDetails(
    root: RootCertificateStatus.Ready,
    uiState: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
) {
    var showSha256 by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Как установить сертификат на компьютер",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Безопаснее всего: отправьте файл себе в мессенджер или по почте и откройте " +
                "его на компьютере. Так его нельзя подменить по пути.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        FilledTonalButton(
            onClick = { onAction(SettingsAction.ShareCertificateClicked) },
            modifier = Modifier.testTag("share-certificate"),
        ) {
            Text("Поделиться сертификатом")
        }
        uiState.certificateShareError?.let { FieldErrorText(it) }
        Text(
            text = "Отпечаток сертификата телефона (SHA-1)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = shortFingerprint(root.fingerprints.sha1),
            modifier = Modifier
                .testTag("secure-mode-sha1-short")
                .semantics {
                    contentDescription = "Начало и конец отпечатка ${shortFingerprint(root.fingerprints.sha1)}"
                },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Если скачиваете сертификат со страницы в браузере, сверьте начало и конец " +
                "поля «Отпечаток» (Thumbprint) в окне установки. Не совпадает - не устанавливайте.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = root.fingerprints.sha1,
            modifier = Modifier
                .testTag("secure-mode-sha1")
                .semantics { contentDescription = "Отпечаток SHA-1 ${root.fingerprints.sha1}" },
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { showSha256 = !showSha256 }) {
            Text(if (showSha256) "Скрыть SHA-256" else "Подробнее: SHA-256")
        }
        if (showSha256) {
            Text(
                text = root.fingerprints.sha256,
                modifier = Modifier.testTag("secure-mode-sha256"),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ResetCertificateButton(uiState, onAction)
    }
}

@Composable
private fun ResetCertificateButton(
    uiState: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
) {
    OutlinedButton(
        onClick = { onAction(SettingsAction.ResetCertificateClicked) },
        enabled = !uiState.certificateResetPending && !uiState.secureModeState.isSaving,
    ) {
        Text(if (uiState.certificateResetPending) "Сброс…" else "Сбросить сертификат")
    }
}

@Composable
private fun SecureModeChangeDialog(
    change: SecureModeChange,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, text, confirm) = when (change) {
        SecureModeChange.Toggle(enabled = true) -> Triple(
            "Включить защищённый режим?",
            "Сервер перезапустится, подключённые браузеры отключатся. Затем откройте на " +
                "компьютере прежний адрес: там будет сертификат и инструкция.",
            "Включить",
        )
        is SecureModeChange.Toggle -> Triple(
            "Выключить защищённый режим?",
            "Сервер перезапустится и будет работать по HTTP без шифрования. Подключённые " +
                "браузеры отключатся.",
            "Выключить",
        )
        SecureModeChange.ResetCertificate -> Triple(
            "Сбросить сертификат?",
            "Будет создан новый сертификат. Компьютеры со старым сертификатом не откроют " +
                "DeviceBridge по HTTPS, пока вы не установите новый. Если сервер запущен, он " +
                "перезапустится. Запомненные браузеры сохранятся.",
            "Сбросить",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** The first and last group of eight, which is enough to tell a substituted file apart. */
internal fun shortFingerprint(sha1: String): String {
    val groups = sha1.split(' ')
    return if (groups.size < 2) sha1 else groups.first() + " … " + groups.last()
}

@Composable
private fun FieldErrorText(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}
