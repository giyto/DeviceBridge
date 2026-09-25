package ru.hznik.devicebridge.feature.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
internal fun TextRoute(
    viewModel: TextViewModel,
    onBack: () -> Unit,
    sharedDraft: SharedTextDraft?,
    onSharedTextConsumed: (Long) -> Unit,
) {
    val context = LocalContext.current
    val platformGateway = remember(context) {
        AndroidTextPlatformGateway(context)
    }
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(sharedDraft?.requestId) {
        sharedDraft?.let {
            viewModel.onAction(TextAction.SharedDraftReceived(it.text))
            onSharedTextConsumed(it.requestId)
        }
    }
    TextScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onPasteRequested = {
            coroutineScope.launch {
                platformGateway.readClipboardText()?.let { clipboardText ->
                    viewModel.onAction(TextAction.DraftChanged(clipboardText))
                }
            }
        },
        onOpenLinkRequested = platformGateway::openHttpLink,
        onBack = onBack,
    )
}
