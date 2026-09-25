package ru.hznik.devicebridge.feature.text

import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferDirection
import ru.hznik.devicebridge.domain.text.TextTransferStatus
import ru.hznik.devicebridge.feature.common.RecipientUiState

data class TextPreviewUiState(
    val content: String,
    val contentKind: TextContentKind,
    val utf8Bytes: Int,
)

data class TextItemUiState(
    val id: TextMessageId,
    val browserLabel: String,
    val content: String,
    val contentKind: TextContentKind,
    val direction: TextTransferDirection,
    val status: TextTransferStatus,
    val timestampEpochMillis: Long,
    val canRetry: Boolean,
    val isRetrying: Boolean,
)

data class TextUiState(
    val isLoading: Boolean = false,
    val draft: String = "",
    val recipients: List<RecipientUiState> = emptyList(),
    val selectedSessionId: BrowserSessionId? = null,
    val recipientSelectionRequired: Boolean = false,
    val preview: TextPreviewUiState? = null,
    val validationMessage: String? = null,
    val items: List<TextItemUiState> = emptyList(),
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
) {
    val canSend: Boolean
        get() = !isSending &&
            selectedSessionId != null &&
            preview != null &&
            validationMessage == null
}

sealed interface TextAction {
    data class DraftChanged(val value: String) : TextAction
    data class SharedDraftReceived(val value: String) : TextAction
    data class RecipientSelected(val sessionId: BrowserSessionId) : TextAction
    data object SendClicked : TextAction
    data class RetryClicked(val messageId: TextMessageId) : TextAction
    data object FeedbackDismissed : TextAction
}
