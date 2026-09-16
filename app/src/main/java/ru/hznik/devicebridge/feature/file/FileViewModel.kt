package ru.hznik.devicebridge.feature.file

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileCommandId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.usecase.ApproveFileTransferUseCase
import ru.hznik.devicebridge.domain.usecase.CancelFileTransferUseCase
import ru.hznik.devicebridge.domain.usecase.CreateFileTransfersUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveBrowserSessionsUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveFileTransfersUseCase
import ru.hznik.devicebridge.domain.usecase.RetryFileTransferUseCase

@HiltViewModel
class FileViewModel private constructor(
    observeBrowserSessions: ObserveBrowserSessionsUseCase,
    observeTransfers: ObserveFileTransfersUseCase,
    private val createTransfers: CreateFileTransfersUseCase,
    private val approveTransfer: ApproveFileTransferUseCase,
    private val cancelTransfer: CancelFileTransferUseCase,
    private val retryTransfer: RetryFileTransferUseCase,
    private val nowEpochMillis: () -> Long,
) : ViewModel() {
    @Inject
    constructor(
        observeBrowserSessions: ObserveBrowserSessionsUseCase,
        observeTransfers: ObserveFileTransfersUseCase,
        createTransfers: CreateFileTransfersUseCase,
        approveTransfer: ApproveFileTransferUseCase,
        cancelTransfer: CancelFileTransferUseCase,
        retryTransfer: RetryFileTransferUseCase,
    ) : this(
        observeBrowserSessions,
        observeTransfers,
        createTransfers,
        approveTransfer,
        cancelTransfer,
        retryTransfer,
        System::currentTimeMillis,
    )

    internal constructor(
        observeBrowserSessions: ObserveBrowserSessionsUseCase,
        observeTransfers: ObserveFileTransfersUseCase,
        createTransfers: CreateFileTransfersUseCase,
        approveTransfer: ApproveFileTransferUseCase,
        cancelTransfer: CancelFileTransferUseCase,
        retryTransfer: RetryFileTransferUseCase,
        nowEpochMillis: () -> Long,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(
        observeBrowserSessions,
        observeTransfers,
        createTransfers,
        approveTransfer,
        cancelTransfer,
        retryTransfer,
        nowEpochMillis,
    )
    private data class LocalState(
        val selection: List<FileSelectionItem> = emptyList(),
        val selectedSessionId: BrowserSessionId? = null,
        val recipientWasLost: Boolean = false,
        val isSubmitting: Boolean = false,
        val errorMessage: String? = null,
        val successMessage: String? = null,
    )

    private val sessions = observeBrowserSessions()
    private val transfers = observeTransfers()
    private val sequence = AtomicLong()
    private val local = MutableStateFlow(
        LocalState(selectedSessionId = sessions.value.sessions.singleOrNull()?.id),
    )
    private val mutableUiState = MutableStateFlow(buildUiState(sessions.value, transfers.value, local.value))
    private val mutableEffects = MutableStateFlow<FileEffect?>(null)

    val uiState: StateFlow<FileUiState> = mutableUiState
    val effects: StateFlow<FileEffect?> = mutableEffects

    init {
        viewModelScope.launch {
            sessions.collectLatest { reconcileRecipient(it.sessions) }
        }
        viewModelScope.launch {
            combine(sessions, transfers, local) { browserState, fileState, localState ->
                buildUiState(browserState, fileState, localState)
            }.collectLatest(mutableUiState::emit)
        }
    }

    fun onAction(action: FileAction) {
        when (action) {
            FileAction.PickFiles -> mutableEffects.value = FileEffect.ChooseFiles
            is FileAction.SelectionReceived -> acceptSelection(action.items, shared = false)
            is FileAction.SharedSelectionReceived -> acceptSelection(action.items, shared = true)
            is FileAction.SelectionRejected -> if (action.count > 0) {
                local.update {
                    it.copy(
                        errorMessage = "Недоступных или слишком больших файлов: ${action.count}.",
                        successMessage = null,
                    )
                }
            }
            is FileAction.RecipientSelected -> selectRecipient(action.sessionId)
            FileAction.ConfirmSend -> confirmSend()
            is FileAction.ApproveIncoming -> mutableEffects.value = FileEffect.ChooseDestination(action.transferId)
            is FileAction.DestinationSelected -> approve(action.transferId, action.destinationId)
            is FileAction.DestinationCancelled -> local.update {
                it.copy(errorMessage = "Папка не выбрана. Файл остаётся в ожидании.", successMessage = null)
            }
            is FileAction.DestinationUnavailable -> local.update {
                it.copy(
                    errorMessage = "Папка недоступна. Выберите другую папку и повторите.",
                    successMessage = null,
                )
            }
            is FileAction.Cancel -> execute(action.transferId, cancelTransfer::invoke)
            is FileAction.Retry -> execute(action.transferId, retryTransfer::invoke)
            is FileAction.Open -> mutableEffects.value = FileEffect.OpenCompleted(action.transferId)
            is FileAction.OpenFailed -> local.update {
                it.copy(errorMessage = action.message, successMessage = null)
            }
            FileAction.DismissFeedback -> local.update { it.copy(errorMessage = null, successMessage = null) }
        }
    }

    fun consumeEffect(effect: FileEffect) {
        if (mutableEffects.value == effect) mutableEffects.value = null
    }

    private fun acceptSelection(items: List<FileSelectionItem>, shared: Boolean) {
        val valid = items.filter { item -> item.sizeBytes >= 0 }
        local.update { current ->
            current.copy(
                selection = valid,
                selectedSessionId = if (shared) null else current.selectedSessionId,
                recipientWasLost = shared || current.recipientWasLost,
                errorMessage = if (valid.size == items.size) null else "Некоторые файлы недоступны.",
                successMessage = null,
            )
        }
    }

    private fun reconcileRecipient(active: List<BrowserSession>) {
        local.update { current ->
            when {
                current.selectedSessionId != null && active.none { it.id == current.selectedSessionId } ->
                    current.copy(
                        selectedSessionId = null,
                        recipientWasLost = true,
                        isSubmitting = false,
                        errorMessage = "Выбранный браузер отключён. Выберите получателя.",
                        successMessage = null,
                    )
                current.selectedSessionId == null && active.size == 1 && !current.recipientWasLost ->
                    current.copy(selectedSessionId = active.single().id)
                else -> current
            }
        }
    }

    private fun selectRecipient(id: BrowserSessionId) {
        if (sessions.value.sessions.none { it.id == id }) return
        local.update {
            it.copy(selectedSessionId = id, recipientWasLost = false, errorMessage = null, successMessage = null)
        }
    }

    private fun confirmSend() {
        val current = local.value
        val sessionId = current.selectedSessionId ?: return
        val generationId = sessions.value.generationId ?: return
        if (current.selection.isEmpty() || current.isSubmitting) return
        local.update { it.copy(isSubmitting = true, errorMessage = null, successMessage = null) }
        viewModelScope.launch {
            val commandId = FileCommandId("android-${nowEpochMillis()}-${sequence.incrementAndGet()}")
            val result = createTransfers(
                CreateFileTransfersRequest(
                    commandId = commandId,
                    generationId = generationId,
                    ownerSessionId = sessionId,
                    batchId = commandId.value,
                    files = current.selection.map { item ->
                        FileTransferMetadata(
                            id = item.transferId,
                            displayName = item.displayName,
                            sizeBytes = item.sizeBytes,
                            mimeType = item.mimeType,
                            sha256 = item.sha256,
                            direction = FileTransferDirection.ANDROID_TO_BROWSER,
                        )
                    },
                ),
            )
            local.update { state ->
                if (result == FileTransferOperationResult.Accepted) {
                    state.copy(
                        selection = emptyList(),
                        isSubmitting = false,
                        errorMessage = null,
                        successMessage = "Файлы добавлены в очередь.",
                    )
                } else {
                    state.copy(isSubmitting = false, errorMessage = result.userMessage(), successMessage = null)
                }
            }
        }
    }

    private fun approve(id: ru.hznik.devicebridge.domain.file.FileTransferId, destination: ru.hznik.devicebridge.domain.file.FileDestinationId) {
        viewModelScope.launch {
            val result = approveTransfer(id, destination)
            if (result != FileTransferOperationResult.Accepted) {
                local.update { it.copy(errorMessage = result.userMessage(), successMessage = null) }
            }
        }
    }

    private fun execute(
        id: ru.hznik.devicebridge.domain.file.FileTransferId,
        operation: suspend (ru.hznik.devicebridge.domain.file.FileTransferId) -> FileTransferOperationResult,
    ) {
        viewModelScope.launch {
            val result = operation(id)
            if (result != FileTransferOperationResult.Accepted) {
                local.update { it.copy(errorMessage = result.userMessage(), successMessage = null) }
            }
        }
    }

    private fun buildUiState(
        sessionState: BrowserSessionState,
        snapshot: FileTransferSnapshot,
        localState: LocalState,
    ): FileUiState {
        val selected = localState.selectedSessionId?.takeIf { id -> sessionState.sessions.any { it.id == id } }
        return FileUiState(
            selection = localState.selection,
            recipients = sessionState.sessions.map { session ->
                FileRecipientUiState(session.id, session.browserLabel, session.sourceIpv4, session.id == selected)
            },
            selectedSessionId = selected,
            recipientSelectionRequired = localState.selection.isNotEmpty() && selected == null,
            transfers = snapshot.items.map { item ->
                FileTransferItemUiState(
                    id = item.metadata.id,
                    displayName = item.metadata.displayName,
                    sizeBytes = item.metadata.sizeBytes,
                    mimeType = item.metadata.mimeType,
                    direction = item.metadata.direction,
                    phase = item.phase,
                    bytesTransferred = item.bytesTransferred,
                    speedBytesPerSecond = item.speedBytesPerSecond,
                    failure = item.failure,
                )
            },
            isSubmitting = localState.isSubmitting,
            errorMessage = localState.errorMessage,
            successMessage = localState.successMessage,
        )
    }
}

private fun FileTransferOperationResult.userMessage(): String = when (this) {
    FileTransferOperationResult.Accepted -> ""
    FileTransferOperationResult.NotFound -> "Передача больше не найдена."
    FileTransferOperationResult.InvalidState -> "Действие недоступно в текущем состоянии."
    FileTransferOperationResult.Conflict -> "Команда конфликтует с уже выполненной операцией."
    is FileTransferOperationResult.Rejected -> "Операцию не удалось выполнить. Проверьте подключение и хранилище."
}
