package ru.hznik.devicebridge.feature.file

import ru.hznik.devicebridge.domain.file.AutoAcceptStatusSource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.receiveAsFlow
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileCommandId
import ru.hznik.devicebridge.domain.file.FileDraftId
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.file.effectiveFileLimitBytes
import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.usecase.ApproveFileTransferUseCase
import ru.hznik.devicebridge.domain.usecase.CancelFileTransferUseCase
import ru.hznik.devicebridge.domain.usecase.CreateFileTransfersUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveBrowserSessionsUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveFileTransfersUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveSettingsUseCase
import ru.hznik.devicebridge.domain.usecase.RetryFileTransferUseCase

@HiltViewModel
class FileViewModel private constructor(
    observeBrowserSessions: ObserveBrowserSessionsUseCase,
    observeTransfers: ObserveFileTransfersUseCase,
    observeSettings: ObserveSettingsUseCase,
    private val createTransfers: CreateFileTransfersUseCase,
    private val approveTransfer: ApproveFileTransferUseCase,
    private val cancelTransfer: CancelFileTransferUseCase,
    private val retryTransfer: RetryFileTransferUseCase,
    private val nowEpochMillis: () -> Long,
    autoAcceptStatus: AutoAcceptStatusSource,
) : ViewModel() {
    @Inject
    constructor(
        observeBrowserSessions: ObserveBrowserSessionsUseCase,
        observeTransfers: ObserveFileTransfersUseCase,
        observeSettings: ObserveSettingsUseCase,
        createTransfers: CreateFileTransfersUseCase,
        approveTransfer: ApproveFileTransferUseCase,
        cancelTransfer: CancelFileTransferUseCase,
        retryTransfer: RetryFileTransferUseCase,
        autoAcceptStatus: AutoAcceptStatusSource,
    ) : this(
        observeBrowserSessions,
        observeTransfers,
        observeSettings,
        createTransfers,
        approveTransfer,
        cancelTransfer,
        retryTransfer,
        System::currentTimeMillis,
        autoAcceptStatus,
    )

    internal constructor(
        observeBrowserSessions: ObserveBrowserSessionsUseCase,
        observeTransfers: ObserveFileTransfersUseCase,
        observeSettings: ObserveSettingsUseCase,
        createTransfers: CreateFileTransfersUseCase,
        approveTransfer: ApproveFileTransferUseCase,
        cancelTransfer: CancelFileTransferUseCase,
        retryTransfer: RetryFileTransferUseCase,
        nowEpochMillis: () -> Long,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
        autoAcceptStatus: AutoAcceptStatusSource = AutoAcceptStatusSource.None,
    ) : this(
        observeBrowserSessions,
        observeTransfers,
        observeSettings,
        createTransfers,
        approveTransfer,
        cancelTransfer,
        retryTransfer,
        nowEpochMillis,
        autoAcceptStatus,
    )
    private data class LocalState(
        val selection: List<FileDraftItem> = emptyList(),
        val selectedSessionId: BrowserSessionId? = null,
        val recipientWasLost: Boolean = false,
        val isSubmitting: Boolean = false,
        val errorMessage: String? = null,
        val successMessage: String? = null,
    )

    private val sessions = observeBrowserSessions()
    private val transfers = observeTransfers()
    private val settings = observeSettings().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        DeviceSettings.defaults(),
    )
    private val autoAcceptFlags = combine(
        autoAcceptStatus.autoAccepted,
        autoAcceptStatus.paused,
    ) { accepted, paused -> AutoAcceptFlags(accepted, paused) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AutoAcceptFlags())
    private val sequence = AtomicLong()
    private val local = MutableStateFlow(
        LocalState(selectedSessionId = sessions.value.sessions.singleOrNull()?.id),
    )
    private val mutableUiState = MutableStateFlow(
        buildUiState(
            sessions.value,
            transfers.value,
            settings.value,
            local.value,
            AutoAcceptFlags(autoAcceptStatus.autoAccepted.value, autoAcceptStatus.paused.value),
        ),
    )
    private val effectChannel = Channel<FileEffect>(Channel.BUFFERED)

    val uiState: StateFlow<FileUiState> = mutableUiState
    val effects = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            sessions.collectLatest { reconcileRecipient(it.sessions) }
        }
        viewModelScope.launch {
            combine(sessions, transfers, settings, local, autoAcceptFlags) {
                    browserState,
                    fileState,
                    settingsState,
                    localState,
                    flags,
                ->
                buildUiState(browserState, fileState, settingsState, localState, flags)
            }.collectLatest(mutableUiState::emit)
        }
    }

    fun onAction(action: FileAction) {
        when (action) {
            FileAction.PickFiles -> effectChannel.trySend(FileEffect.ChooseFiles)
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
            is FileAction.RemoveDraftItem -> removeDraftItem(action.draftId)
            FileAction.ClearDraft -> clearDraft()
            is FileAction.RecipientSelected -> selectRecipient(action.sessionId)
            FileAction.ConfirmSend -> confirmSend()
            is FileAction.ApproveIncoming -> {
                val saved = settings.value.destinationTree?.value
                effectChannel.trySend(
                    if (saved == null) {
                        FileEffect.ChooseDestination(action.transferId)
                    } else {
                        FileEffect.UseDefaultDestination(action.transferId, saved)
                    },
                )
            }
            is FileAction.ChangeIncomingDestination ->
                effectChannel.trySend(FileEffect.ChooseDestination(action.transferId))
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
            is FileAction.Open -> effectChannel.trySend(FileEffect.OpenCompleted(action.transferId))
            is FileAction.OpenFailed -> local.update {
                it.copy(errorMessage = action.message, successMessage = null)
            }
            FileAction.DismissFeedback -> local.update { it.copy(errorMessage = null, successMessage = null) }
        }
    }


    private fun acceptSelection(items: List<FileDraftItem>, shared: Boolean) {
        val valid = items.filter { item -> item.sizeBytes >= 0 }
        items.filterNot { it in valid }.forEach { it.sourceLease.release() }
        local.update { current ->
            val existingKeys = current.selection.mapTo(mutableSetOf()) { it.dedupeKey }
            val appended = buildList {
                valid.forEach { item ->
                    if (existingKeys.add(item.dedupeKey)) {
                        add(item)
                    } else {
                        item.sourceLease.release()
                    }
                }
            }
            current.copy(
                selection = current.selection + appended,
                selectedSessionId = if (shared && appended.isNotEmpty()) null else current.selectedSessionId,
                recipientWasLost = (shared && appended.isNotEmpty()) || current.recipientWasLost,
                errorMessage = if (valid.size == items.size) null else "Некоторые файлы недоступны.",
                successMessage = null,
            )
        }
    }

    private fun removeDraftItem(draftId: FileDraftId) {
        val current = local.value
        if (current.isSubmitting) return
        val removed = current.selection.firstOrNull { it.id == draftId } ?: return
        local.update { state ->
            state.copy(
                selection = state.selection.filterNot { it.id == draftId },
                errorMessage = null,
                successMessage = null,
            )
        }
        removed.sourceLease.release()
    }

    private fun clearDraft() {
        val current = local.value
        if (current.isSubmitting || current.selection.isEmpty()) return
        local.update {
            it.copy(selection = emptyList(), errorMessage = null, successMessage = null)
        }
        current.selection.forEach { it.sourceLease.release() }
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
            val prepared = current.selection.mapIndexed { index, item ->
                PreparedDraftTransfer(
                    item = item,
                    transferId = FileTransferId("${commandId.value}-${index + 1}"),
                )
            }
            val promoted = mutableListOf<PreparedDraftTransfer>()
            val promotionSucceeded = prepared.all { transfer ->
                transfer.item.sourceLease.promote(transfer.transferId).also { promotedNow ->
                    if (promotedNow) promoted += transfer
                }
            }
            if (!promotionSucceeded) {
                promoted.forEach { it.item.sourceLease.rollback(it.transferId) }
                local.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = "Не удалось подготовить выбранные файлы.",
                        successMessage = null,
                    )
                }
                return@launch
            }
            val result = runCatching {
                createTransfers(
                    CreateFileTransfersRequest(
                        commandId = commandId,
                        generationId = generationId,
                        ownerSessionId = sessionId,
                        batchId = commandId.value,
                        files = prepared.map { transfer ->
                            val item = transfer.item
                        FileTransferMetadata(
                            id = transfer.transferId,
                            displayName = item.displayName,
                            sizeBytes = item.sizeBytes,
                            mimeType = item.mimeType,
                            sha256 = item.sha256,
                            direction = FileTransferDirection.ANDROID_TO_BROWSER,
                        )
                    },
                    ),
                )
            }.getOrDefault(FileTransferOperationResult.InvalidState)
            local.update { state ->
                if (result == FileTransferOperationResult.Accepted) {
                    prepared.forEach { it.item.sourceLease.commit(it.transferId) }
                    val sentIds = prepared.mapTo(mutableSetOf()) { it.item.id }
                    state.copy(
                        selection = state.selection.filterNot { it.id in sentIds },
                        isSubmitting = false,
                        errorMessage = null,
                        successMessage = "Файлы добавлены в очередь.",
                    )
                } else {
                    prepared.forEach { it.item.sourceLease.rollback(it.transferId) }
                    state.copy(isSubmitting = false, errorMessage = result.userMessage(), successMessage = null)
                }
            }
        }
    }

    override fun onCleared() {
        local.value.selection.forEach { it.sourceLease.release() }
        super.onCleared()
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
        settingsState: DeviceSettings,
        localState: LocalState,
        flags: AutoAcceptFlags,
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
                    senderLabel = sessionState.sessions
                        .firstOrNull { it.id == item.ownerSessionId }
                        ?.browserLabel,
                    autoAccepted = item.metadata.id in flags.accepted,
                    autoAcceptPaused = item.metadata.id in flags.paused,
                )
            },
            isSubmitting = localState.isSubmitting,
            errorMessage = localState.errorMessage,
            successMessage = localState.successMessage,
            hasDefaultDestination = settingsState.destinationTree != null,
            effectiveFileLimitBytes = effectiveFileLimitBytes(
                settingsState.effectiveFileLimitBytes,
            ),
        )
    }
}

private data class AutoAcceptFlags(
    val accepted: Set<ru.hznik.devicebridge.domain.file.FileTransferId> = emptySet(),
    val paused: Set<ru.hznik.devicebridge.domain.file.FileTransferId> = emptySet(),
)

private data class PreparedDraftTransfer(
    val item: FileDraftItem,
    val transferId: FileTransferId,
)

private fun FileTransferOperationResult.userMessage(): String = when (this) {
    FileTransferOperationResult.Accepted -> ""
    FileTransferOperationResult.NotFound -> "Передача больше не найдена."
    FileTransferOperationResult.InvalidState -> "Действие недоступно в текущем состоянии."
    FileTransferOperationResult.Conflict -> "Команда конфликтует с уже выполненной операцией."
    is FileTransferOperationResult.Rejected -> "Операцию не удалось выполнить. Проверьте подключение и хранилище."
}
