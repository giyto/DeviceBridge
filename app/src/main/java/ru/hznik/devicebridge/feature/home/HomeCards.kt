package ru.hznik.devicebridge.feature.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.hznik.devicebridge.core.ui.BridgeIcons

@Composable
internal fun ConnectionGuideCard(
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
                    if (!uiState.hasConnectedBrowser) {
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
internal fun PairingCodeCard(
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
internal fun MessageCard(
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
