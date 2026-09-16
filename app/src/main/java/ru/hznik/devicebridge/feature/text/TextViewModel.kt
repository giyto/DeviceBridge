package ru.hznik.devicebridge.feature.text

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.text.SendTextRequest
import ru.hznik.devicebridge.domain.text.TextContentClassifier
import ru.hznik.devicebridge.domain.text.TextContentValidation
import ru.hznik.devicebridge.domain.text.TextContentValidator
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferFailureReason
import ru.hznik.devicebridge.domain.text.TextTransferItem
import ru.hznik.devicebridge.domain.text.TextTransferRejection
import ru.hznik.devicebridge.domain.text.TextTransferResult
import ru.hznik.devicebridge.domain.text.TextTransferState
import ru.hznik.devicebridge.domain.text.TextTransferStatus
import ru.hznik.devicebridge.domain.usecase.ObserveBrowserSessionsUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveTextTransfersUseCase
import ru.hznik.devicebridge.domain.usecase.RetryTextTransferUseCase
import ru.hznik.devicebridge.domain.usecase.SendTextToBrowserUseCase

@HiltViewModel
class TextViewModel @Inject constructor(
    observeBrowserSessions: ObserveBrowserSessionsUseCase,
    observeTextTransfers: ObserveTextTransfersUseCase,
    private val sendText: SendTextToBrowserUseCase,
    private val retryText: RetryTextTransferUseCase,
) : ViewModel() {
    private data class LocalState(
        val draft: String = "",
        val selectedSessionId: BrowserSessionId? = null,
        val recipientWasLost: Boolean = false,
        val isSending: Boolean = false,
        val retryingMessageIds: Set<TextMessageId> = emptySet(),
        val errorMessage: String? = null,
        val successMessage: String? = null,
    )

    private val browserSessions = observeBrowserSessions()
    private val transfers = observeTextTransfers()
    private val localState = MutableStateFlow(
        LocalState(
            selectedSessionId = browserSessions.value.sessions.singleOrNull()?.id,
        ),
    )
    private val mutableUiState = MutableStateFlow(
        buildUiState(
            sessions = browserSessions.value.sessions,
            transfers = transfers.value,
            local = localState.value,
        ),
    )

    val uiState: StateFlow<TextUiState> = mutableUiState

    init {
        viewModelScope.launch {
            browserSessions.collectLatest { sessionState ->
                reconcileRecipient(sessionState.sessions)
            }
        }
        viewModelScope.launch {
            combine(browserSessions, transfers, localState) { sessionState, textState, local ->
                buildUiState(sessionState.sessions, textState, local)
            }.collectLatest(mutableUiState::emit)
        }
    }

    fun onAction(action: TextAction) {
        when (action) {
            is TextAction.DraftChanged -> localState.update {
                it.copy(
                    draft = action.value,
                    errorMessage = null,
                    successMessage = null,
                )
            }
            is TextAction.SharedDraftReceived -> localState.update {
                it.copy(
                    draft = action.value,
                    selectedSessionId = null,
                    recipientWasLost = true,
                    isSending = false,
                    errorMessage = null,
                    successMessage = null,
                )
            }
            is TextAction.RecipientSelected -> selectRecipient(action.sessionId)
            TextAction.SendClicked -> sendDraft()
            is TextAction.RetryClicked -> retry(action.messageId)
            TextAction.FeedbackDismissed -> localState.update {
                it.copy(errorMessage = null, successMessage = null)
            }
        }
    }

    private fun reconcileRecipient(sessions: List<BrowserSession>) {
        localState.update { local ->
            val selected = local.selectedSessionId
            when {
                selected != null && sessions.none { it.id == selected } -> local.copy(
                    selectedSessionId = null,
                    recipientWasLost = true,
                    isSending = false,
                    errorMessage = RECIPIENT_DISCONNECTED_MESSAGE,
                    successMessage = null,
                )
                selected == null && sessions.size == 1 && !local.recipientWasLost -> local.copy(
                    selectedSessionId = sessions.single().id,
                )
                else -> local
            }
        }
    }

    private fun selectRecipient(sessionId: BrowserSessionId) {
        if (browserSessions.value.sessions.none { it.id == sessionId }) {
            localState.update {
                it.copy(
                    selectedSessionId = null,
                    recipientWasLost = true,
                    errorMessage = RECIPIENT_DISCONNECTED_MESSAGE,
                    successMessage = null,
                )
            }
            return
        }
        localState.update {
            it.copy(
                selectedSessionId = sessionId,
                recipientWasLost = false,
                errorMessage = null,
                successMessage = null,
            )
        }
    }

    private fun sendDraft() {
        val current = mutableUiState.value
        val sessionId = current.selectedSessionId ?: return
        if (!current.canSend) return
        val content = current.draft
        localState.update {
            it.copy(isSending = true, errorMessage = null, successMessage = null)
        }
        viewModelScope.launch {
            val result = sendText(SendTextRequest(sessionId, content))
            localState.update { local ->
                when (result) {
                    is TextTransferResult.Accepted -> local.afterAcceptedSend(result.item, content)
                    is TextTransferResult.Rejected -> local.copy(
                        isSending = false,
                        errorMessage = result.reason.userMessage(),
                        successMessage = null,
                    )
                }
            }
        }
    }

    private fun retry(messageId: TextMessageId) {
        val item = transfers.value.items.firstOrNull { it.id == messageId } ?: return
        if (item.status != TextTransferStatus.FAILED) return
        val sessionAvailable = browserSessions.value.sessions.any { it.id == item.sessionId }
        if (!sessionAvailable) {
            localState.update {
                it.copy(
                    errorMessage = "Браузер для повторной отправки отключён.",
                    successMessage = null,
                )
            }
            return
        }
        if (messageId in localState.value.retryingMessageIds) return
        localState.update {
            it.copy(
                retryingMessageIds = it.retryingMessageIds + messageId,
                errorMessage = null,
                successMessage = null,
            )
        }
        viewModelScope.launch {
            val result = retryText(messageId)
            localState.update { local ->
                val withoutPending = local.copy(
                    retryingMessageIds = local.retryingMessageIds - messageId,
                )
                when (result) {
                    is TextTransferResult.Accepted -> {
                        if (result.item.status == TextTransferStatus.DELIVERED) {
                            withoutPending.copy(
                                errorMessage = null,
                                successMessage = "Повторная отправка выполнена.",
                            )
                        } else {
                            withoutPending.copy(
                                errorMessage = result.item.failureReason.userMessage(),
                                successMessage = null,
                            )
                        }
                    }
                    is TextTransferResult.Rejected -> withoutPending.copy(
                        errorMessage = result.reason.userMessage(),
                        successMessage = null,
                    )
                }
            }
        }
    }

    private fun buildUiState(
        sessions: List<BrowserSession>,
        transfers: TextTransferState,
        local: LocalState,
    ): TextUiState {
        val selected = local.selectedSessionId?.takeIf { id -> sessions.any { it.id == id } }
        val validation = TextContentValidator.validate(local.draft)
        val preview = when (validation) {
            is TextContentValidation.Valid -> TextPreviewUiState(
                content = local.draft,
                contentKind = TextContentClassifier.classify(local.draft),
                utf8Bytes = validation.utf8Bytes,
            )
            TextContentValidation.Empty,
            is TextContentValidation.TooLarge,
            -> null
        }
        val validationMessage = when (validation) {
            TextContentValidation.Empty ->
                "Введите текст.".takeIf { local.draft.isNotEmpty() }
            is TextContentValidation.TooLarge ->
                "Текст превышает лимит 100 КБ."
            is TextContentValidation.Valid -> null
        }
        val activeSessionIds = sessions.mapTo(mutableSetOf(), BrowserSession::id)
        return TextUiState(
            draft = local.draft,
            recipients = sessions.map { session ->
                TextRecipientUiState(
                    id = session.id,
                    browserLabel = session.browserLabel,
                    sourceIpv4 = session.sourceIpv4,
                    selected = session.id == selected,
                )
            },
            selectedSessionId = selected,
            recipientSelectionRequired =
                local.recipientWasLost || (sessions.size > 1 && selected == null),
            preview = preview,
            validationMessage = validationMessage,
            items = transfers.items.map { item ->
                item.toUiState(
                    sessionAvailable = item.sessionId in activeSessionIds,
                    isRetrying = item.id in local.retryingMessageIds,
                )
            },
            isSending = local.isSending,
            errorMessage = local.errorMessage,
            successMessage = local.successMessage,
        )
    }

    private fun LocalState.afterAcceptedSend(
        item: TextTransferItem,
        sentContent: String,
    ): LocalState = when (item.status) {
        TextTransferStatus.DELIVERED -> copy(
            draft = draft.takeUnless { it == sentContent }.orEmpty(),
            isSending = false,
            errorMessage = null,
            successMessage = "Текст доставлен.",
        )
        TextTransferStatus.FAILED -> copy(
            isSending = false,
            errorMessage = item.failureReason.userMessage(),
            successMessage = null,
        )
        TextTransferStatus.PENDING,
        TextTransferStatus.SENDING,
        -> copy(
            isSending = false,
            errorMessage = null,
            successMessage = "Отправка начата.",
        )
    }

    private fun TextTransferItem.toUiState(
        sessionAvailable: Boolean,
        isRetrying: Boolean,
    ): TextItemUiState = TextItemUiState(
        id = id,
        sessionId = sessionId,
        browserLabel = browserLabel,
        content = content,
        contentKind = contentKind,
        direction = direction,
        status = status,
        timestampEpochMillis = createdAtEpochMillis,
        canRetry = status == TextTransferStatus.FAILED && sessionAvailable && !isRetrying,
        isRetrying = isRetrying,
    )

    private fun TextTransferRejection.userMessage(): String = when (this) {
        TextTransferRejection.EMPTY_CONTENT -> "Введите текст для отправки."
        TextTransferRejection.CONTENT_TOO_LARGE -> "Текст превышает лимит 100 КБ."
        TextTransferRejection.SESSION_UNAVAILABLE ->
            "Получатель недоступен. Выберите активный браузер."
        TextTransferRejection.GENERATION_CLOSED -> "Сервер остановлен. Запустите его снова."
        TextTransferRejection.MESSAGE_CONFLICT -> "Идентификатор сообщения уже использован."
        TextTransferRejection.MESSAGE_NOT_FOUND -> "Сообщение для повтора не найдено."
    }

    private fun TextTransferFailureReason?.userMessage(): String = when (this) {
        TextTransferFailureReason.CONNECTION_LOST ->
            "Соединение потеряно. Восстановите browser session и повторите."
        TextTransferFailureReason.SESSION_CLOSED ->
            "Браузер не подключён. Обновите страницу и проверьте доступ VPN к локальной сети."
        TextTransferFailureReason.PROTOCOL_ERROR ->
            "Браузер отклонил сообщение из-за ошибки протокола."
        TextTransferFailureReason.UNKNOWN,
        null,
        -> "Не удалось доставить текст. Повторите отправку."
    }

    private companion object {
        const val RECIPIENT_DISCONNECTED_MESSAGE =
            "Выбранный браузер отключён. Выберите получателя."
    }
}
