package ru.hznik.devicebridge.feature.text

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferDirection
import ru.hznik.devicebridge.domain.text.TextTransferStatus
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
private val transferTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")


@Composable
fun TextScreen(
    uiState: TextUiState,
    modifier: Modifier = Modifier,
    onAction: (TextAction) -> Unit = {},
    onPasteRequested: () -> Unit = {},
    onOpenLinkRequested: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 20.dp, vertical = 24.dp)),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        onBack?.let { navigateBack ->
            TextButton(
                onClick = navigateBack,
                modifier = Modifier.semantics {
                    contentDescription = "Вернуться на главный экран"
                },
            ) {
                Text("← Назад")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Текст и ссылки",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Передавайте данные напрямую между телефоном и доверенным браузером.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (uiState.isLoading) {
            StateCard(
                title = "Загружаем передачу текста…",
                message = "Проверяем активные локальные подключения.",
            )
            return@Column
        }

        uiState.errorMessage?.let {
            FeedbackCard(
                message = it,
                isError = true,
                onDismiss = { onAction(TextAction.FeedbackDismissed) },
            )
        }
        uiState.successMessage?.let {
            FeedbackCard(
                message = it,
                isError = false,
                onDismiss = { onAction(TextAction.FeedbackDismissed) },
            )
        }

        RecipientSection(
            recipients = uiState.recipients,
            selectionRequired = uiState.recipientSelectionRequired,
            onSelect = { onAction(TextAction.RecipientSelected(it)) },
        )
        EditorSection(
            uiState = uiState,
            onAction = onAction,
            onPasteRequested = onPasteRequested,
            onOpenLinkRequested = onOpenLinkRequested,
        )
        TransferFeed(
            items = uiState.items,
            onRetry = { onAction(TextAction.RetryClicked(it)) },
            onOpenLinkRequested = onOpenLinkRequested,
        )
    }
}

@Composable
private fun RecipientSection(
    recipients: List<TextRecipientUiState>,
    selectionRequired: Boolean,
    onSelect: (BrowserSessionId) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Получатель",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        when {
            recipients.isEmpty() -> StateCard(
                title = "Нет подключённых браузеров",
                message = "Вернитесь на главный экран и подключите доверенный браузер.",
            )

            recipients.size == 1 -> RecipientCard(
                recipient = recipients.single(),
                selectable = selectionRequired,
                onSelect = onSelect,
            )

            else -> {
                Text(
                    text = if (selectionRequired) {
                        "Выберите браузер для этой отправки."
                    } else {
                        "Текст будет отправлен только выбранному браузеру."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selectionRequired) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                recipients.forEach { recipient ->
                    RecipientCard(
                        recipient = recipient,
                        selectable = true,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecipientCard(
    recipient: TextRecipientUiState,
    selectable: Boolean,
    onSelect: (BrowserSessionId) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selectable) {
                    Modifier
                        .selectable(
                            selected = recipient.selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(recipient.id) },
                        )
                        .semantics {
                            contentDescription = "Выбрать ${recipient.browserLabel}"
                        }
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (recipient.selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        border = if (recipient.selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectable) {
                RadioButton(
                    selected = recipient.selected,
                    onClick = null,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(recipient.browserLabel, fontWeight = FontWeight.SemiBold)
                Text(
                    text = recipient.sourceIpv4,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EditorSection(
    uiState: TextUiState,
    onAction: (TextAction) -> Unit,
    onPasteRequested: () -> Unit,
    onOpenLinkRequested: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                isTraversalGroup = true
                contentDescription = "Область создания сообщения"
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
            Text(
                text = "Новое сообщение",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = uiState.draft,
                onValueChange = { onAction(TextAction.DraftChanged(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Текст для отправки" },
                label = { Text("Текст или ссылка") },
                supportingText = uiState.validationMessage?.let { message ->
                    { Text(message) }
                },
                isError = uiState.validationMessage != null,
                minLines = 4,
                maxLines = 10,
            )
            OutlinedButton(
                onClick = onPasteRequested,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Вставить текст из буфера" },
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                Text("Вставить")
            }

            uiState.preview?.let { preview ->
                PreviewCard(
                    preview = preview,
                    onOpenLinkRequested = onOpenLinkRequested,
                )
            }

            Button(
                onClick = { onAction(TextAction.SendClicked) },
                enabled = uiState.canSend,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Отправить текст в выбранный браузер" },
                contentPadding = PaddingValues(vertical = 15.dp),
            ) {
                Text(if (uiState.isSending) "Отправляем…" else "Отправить")
            }
        }
    }
}

@Composable
private fun PreviewCard(
    preview: TextPreviewUiState,
    onOpenLinkRequested: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Предпросмотр",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Тип: ${preview.contentKind.label()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = preview.content,
                modifier = Modifier.semantics {
                    contentDescription = "Предпросмотр содержимого: ${preview.content}"
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${preview.utf8Bytes} байт",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (preview.contentKind == TextContentKind.LINK) {
                OutlinedButton(
                    onClick = { onOpenLinkRequested(preview.content) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Открыть ссылку" },
                ) {
                    Text("Открыть ссылку")
                }
            }
        }
    }
}

@Composable
private fun TransferFeed(
    items: List<TextItemUiState>,
    onRetry: (TextMessageId) -> Unit,
    onOpenLinkRequested: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Текущая лента",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (items.isEmpty()) {
            StateCard(
                title = "Передач пока нет",
                message = "Здесь появятся сообщения текущего запуска сервера.",
            )
        } else {
            items.forEach { item ->
                TransferItemCard(
                    item = item,
                    onRetry = onRetry,
                    onOpenLinkRequested = onOpenLinkRequested,
                )
            }
        }
    }
}

@Composable
private fun TransferItemCard(
    item: TextItemUiState,
    onRetry: (TextMessageId) -> Unit,
    onOpenLinkRequested: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = item.participantLabel(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = item.direction.label(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Время: ${item.timestampEpochMillis.transferTimeLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = item.status.label(),
                style = MaterialTheme.typography.labelLarge,
                color = if (item.status == TextTransferStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.semantics {
                    contentDescription = "Статус передачи текста: ${item.status.label()}"
                    liveRegion = LiveRegionMode.Polite
                },
            )
            HorizontalDivider()
            Text(text = item.content, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Тип: ${item.contentKind.label()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.contentKind == TextContentKind.LINK) {
                OutlinedButton(
                    onClick = { onOpenLinkRequested(item.content) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription =
                                "Открыть ссылку от ${item.browserLabel}"
                        },
                ) {
                    Text("Открыть ссылку")
                }
            }
            if (item.canRetry) {
                OutlinedButton(
                    onClick = { onRetry(item.id) },
                    enabled = !item.isRetrying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Повторить отправку в ${item.browserLabel}"
                        },
                ) {
                    Text(if (item.isRetrying) "Повторяем…" else "Повторить")
                }
            }
        }
    }
}

@Composable
private fun FeedbackCard(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StateCard(title: String, message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun TextItemUiState.participantLabel(): String = when (direction) {
    TextTransferDirection.ANDROID_TO_BROWSER -> "Получатель: $browserLabel"
    TextTransferDirection.BROWSER_TO_ANDROID -> "Отправитель: $browserLabel"
}

private fun Long.transferTimeLabel(): String =
    Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(transferTimeFormatter)

private fun TextContentKind.label(): String = when (this) {
    TextContentKind.TEXT -> "Текст"
    TextContentKind.LINK -> "Ссылка"
}

private fun TextTransferDirection.label(): String = when (this) {
    TextTransferDirection.ANDROID_TO_BROWSER -> "На компьютер"
    TextTransferDirection.BROWSER_TO_ANDROID -> "На телефон"
}

private fun TextTransferStatus.label(): String = when (this) {
    TextTransferStatus.PENDING -> "Ожидает"
    TextTransferStatus.SENDING -> "Отправляется"
    TextTransferStatus.DELIVERED -> "Доставлено"
    TextTransferStatus.FAILED -> "Ошибка"
}

@Preview(name = "Текст — светлая", showBackground = true)
@Composable
private fun TextScreenLightPreview() {
    DeviceBridgeTheme(darkTheme = false) {
        TextScreen(
            uiState = TextUiState(
                draft = "https://example.com",
                recipients = listOf(
                    TextRecipientUiState(
                        id = BrowserSessionId("preview-browser"),
                        browserLabel = "Яндекс Браузер",
                        sourceIpv4 = "192.168.1.24",
                        selected = true,
                    ),
                ),
                selectedSessionId = BrowserSessionId("preview-browser"),
                preview = TextPreviewUiState(
                    content = "https://example.com",
                    contentKind = TextContentKind.LINK,
                    utf8Bytes = 19,
                ),
            ),
        )
    }
}

@Preview(
    name = "Текст — тёмная",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun TextScreenDarkPreview() {
    DeviceBridgeTheme(darkTheme = true) {
        TextScreen(
            uiState = TextUiState(
                items = listOf(
                    TextItemUiState(
                        id = TextMessageId("preview-message"),
                        sessionId = BrowserSessionId("preview-browser"),
                        browserLabel = "Edge",
                        content = "Локальная передача работает",
                        contentKind = TextContentKind.TEXT,
                        direction = TextTransferDirection.BROWSER_TO_ANDROID,
                        status = TextTransferStatus.DELIVERED,
                        timestampEpochMillis = 1L,
                        canRetry = false,
                        isRetrying = false,
                    ),
                ),
            ),
        )
    }
}
