package ru.hznik.devicebridge.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.hznik.devicebridge.domain.error.FailureSeverity
import ru.hznik.devicebridge.domain.error.RecoveryAction
import ru.hznik.devicebridge.domain.error.UserFacingFailure
import ru.hznik.devicebridge.ui.theme.BridgeBorders
import ru.hznik.devicebridge.ui.theme.BridgeSpacing
import ru.hznik.devicebridge.ui.theme.bridgeStatusColors

enum class StateTone {
    NEUTRAL,
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

@Composable
fun StateSurface(
    statusLabel: String,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    tone: StateTone = StateTone.NEUTRAL,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = stateSurfaceColors(tone)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = BridgeBorders.subtle,
                color = colors.accent.copy(alpha = 0.45f),
                shape = MaterialTheme.shapes.large,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "$statusLabel. $title. $message"
            },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.container),
    ) {
        Column(
            modifier = Modifier.padding(BridgeSpacing.large),
            verticalArrangement = Arrangement.spacedBy(BridgeSpacing.small),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BridgeSpacing.small),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(colors.accent, CircleShape),
                )
                Text(
                    text = statusLabel,
                    color = colors.content,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = title,
                color = colors.content,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                color = colors.content.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodyMedium,
            )
            actions()
        }
    }
}

@Composable
fun FailureCard(
    title: String,
    message: String,
    failure: UserFacingFailure,
    onRecoveryAction: (RecoveryAction) -> Unit,
    onCopyTechnicalDetails: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    StateSurface(
        statusLabel = if (failure.severity == FailureSeverity.RECOVERABLE) {
            "Можно восстановить"
        } else {
            "Требуется действие"
        },
        title = title,
        message = message,
        tone = if (failure.severity == FailureSeverity.RECOVERABLE) {
            StateTone.WARNING
        } else {
            StateTone.ERROR
        },
        modifier = modifier,
    ) {
        failure.recoveryActions
            .sortedBy(RecoveryAction::ordinal)
            .forEachIndexed { index, action ->
                val label = action.label()
                if (index == 0) {
                    PrimaryActionButton(
                        label = label,
                        onClick = { onRecoveryAction(action) },
                        contentDescription = "Действие восстановления: $label",
                    )
                } else {
                    SecondaryActionButton(
                        label = label,
                        onClick = { onRecoveryAction(action) },
                        contentDescription = "Действие восстановления: $label",
                    )
                }
            }
        if (onCopyTechnicalDetails != null) {
            FailureTechnicalDetails(
                failure = failure,
                onCopy = onCopyTechnicalDetails,
            )
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StateSurface(
        statusLabel = "Пусто",
        title = title,
        message = message,
        modifier = modifier,
    ) {
        if (actionLabel != null && onAction != null) {
            PrimaryActionButton(label = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun LoadingState(
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(BridgeSpacing.large)
            .semantics(mergeDescendants = true) {
                contentDescription = label
            },
        horizontalArrangement = Arrangement.spacedBy(BridgeSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun OperationalCard(
    title: String,
    metadata: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(BridgeSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(BridgeSpacing.small),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            content()
            actions()
        }
    }
}

@Composable
fun PrimaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    contentDescription: String = "Основное действие: $label",
) {
    val effectiveInteractionSource = interactionSource ?: remember {
        MutableInteractionSource()
    }
    val isFocused by effectiveInteractionSource.collectIsFocusedAsState()
    val shape = MaterialTheme.shapes.extraLarge
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = effectiveInteractionSource,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (isFocused) BridgeBorders.focused else 0.dp,
                color = if (isFocused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
                shape = shape,
            )
            .semantics { this.contentDescription = contentDescription },
    ) {
        Text(label)
    }
}
@Composable
fun SecondaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = "Дополнительное действие: $label",
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = contentDescription },
    ) {
        Text(label)
    }
}

private data class StateSurfaceColors(
    val accent: Color,
    val container: Color,
    val content: Color,
)

@Composable
private fun stateSurfaceColors(tone: StateTone): StateSurfaceColors {
    val status = MaterialTheme.bridgeStatusColors
    return when (tone) {
        StateTone.NEUTRAL -> StateSurfaceColors(
            accent = MaterialTheme.colorScheme.outline,
            container = MaterialTheme.colorScheme.surfaceContainer,
            content = MaterialTheme.colorScheme.onSurface,
        )
        StateTone.INFO -> StateSurfaceColors(
            accent = status.info,
            container = status.infoContainer,
            content = status.onInfoContainer,
        )
        StateTone.SUCCESS -> StateSurfaceColors(
            accent = status.success,
            container = status.successContainer,
            content = status.onSuccessContainer,
        )
        StateTone.WARNING -> StateSurfaceColors(
            accent = status.warning,
            container = status.warningContainer,
            content = status.onWarningContainer,
        )
        StateTone.ERROR -> StateSurfaceColors(
            accent = MaterialTheme.colorScheme.error,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

private fun RecoveryAction.label(): String = when (this) {
    RecoveryAction.REQUEST_PERMISSION -> "Запросить разрешение"
    RecoveryAction.OPEN_SETTINGS -> "Открыть настройки"
    RecoveryAction.CONNECT_TO_LOCAL_NETWORK -> "Подключиться к локальной сети"
    RecoveryAction.START_SERVER -> "Запустить сервер"
    RecoveryAction.RETRY -> "Повторить"
    RecoveryAction.MANAGE_SESSIONS -> "Управлять подключениями"
    RecoveryAction.PAIR_AGAIN -> "Подключить заново"
    RecoveryAction.SELECT_SESSION -> "Выбрать браузер"
    RecoveryAction.UPDATE_CLIENT -> "Обновить клиент"
    RecoveryAction.START_NEW_OPERATION -> "Начать заново"
    RecoveryAction.EDIT_CONTENT -> "Изменить текст"
    RecoveryAction.SELECT_FILE -> "Выбрать файл"
    RecoveryAction.SELECT_DESTINATION -> "Выбрать папку"
    RecoveryAction.REDUCE_SELECTION -> "Уменьшить выбор"
    RecoveryAction.EDIT_SETTING -> "Изменить настройку"
}
