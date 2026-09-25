package ru.hznik.devicebridge.core.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ru.hznik.devicebridge.ui.theme.BridgeBorders
import ru.hznik.devicebridge.ui.theme.BridgeSpacing

@Composable
fun PrimaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
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
        enabled = enabled && !loading,
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
            .semantics {
                this.contentDescription = contentDescription
                if (loading) stateDescription = "Выполняется"
            },
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(modifier = Modifier.width(BridgeSpacing.small))
        }
        Text(label)
    }
}
/**
 * Calm filled action for repeated, low-risk commands (for example "Save" in forms)
 * where a saturated primary button would dominate the screen.
 */
@Composable
fun TonalActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    contentDescription: String = "Действие: $label",
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                this.contentDescription = contentDescription
                if (loading) stateDescription = "Выполняется"
            },
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.width(BridgeSpacing.small))
        }
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
