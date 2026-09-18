package ru.hznik.devicebridge.feature.file

import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileDraftId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES
import ru.hznik.devicebridge.domain.session.BrowserSessionId

typealias DraftSourceLease = ru.hznik.devicebridge.domain.file.DraftSourceLease

data class FileDraftItem(
    val id: FileDraftId,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val sha256: String,
    internal val sourceIdentity: String,
    internal val sourceLease: DraftSourceLease,
) {
    internal val dedupeKey: String
        get() = "$sourceIdentity\u0000$sizeBytes\u0000${sha256.lowercase()}"
}

data class FileRecipientUiState(
    val id: BrowserSessionId,
    val browserLabel: String,
    val sourceIpv4: String,
    val selected: Boolean,
)

data class FileTransferItemUiState(
    val id: FileTransferId,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val direction: FileTransferDirection,
    val phase: FileTransferPhase,
    val bytesTransferred: Long,
    val speedBytesPerSecond: Long,
    val failure: FileTransferFailure?,
) {
    val progress: Float
        get() = if (sizeBytes == 0L) {
            if (phase == FileTransferPhase.COMPLETED) 1f else 0f
        } else {
            (bytesTransferred.toDouble() / sizeBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }
    val canCancel: Boolean get() = !phase.isTerminal
    val canRetry: Boolean get() = phase == FileTransferPhase.FAILED || phase == FileTransferPhase.CANCELLED
    val canOpen: Boolean get() = phase == FileTransferPhase.COMPLETED && direction == FileTransferDirection.BROWSER_TO_ANDROID
    val awaitsApproval: Boolean get() = direction == FileTransferDirection.BROWSER_TO_ANDROID && phase == FileTransferPhase.CONNECTING
    val failureMessage: String? get() = when (failure) {
        FileTransferFailure.ChecksumMismatch ->
            "Контрольная сумма не совпала. Повторите передачу."
        FileTransferFailure.StreamFailed ->
            "Передача прервалась. Незавершённый файл удалён, если хранилище это разрешило."
        FileTransferFailure.SessionUnavailable ->
            "Браузер отключён. Подключите его снова и повторите передачу."
        FileTransferFailure.StorageUnavailable ->
            "Папка недоступна или на устройстве недостаточно места."
        FileTransferFailure.CapacityReached ->
            "Очередь передач заполнена. Завершите или отмените другие файлы."
        null -> null
    }
}

data class FileUiState(
    val selection: List<FileDraftItem> = emptyList(),
    val recipients: List<FileRecipientUiState> = emptyList(),
    val selectedSessionId: BrowserSessionId? = null,
    val recipientSelectionRequired: Boolean = false,
    val transfers: List<FileTransferItemUiState> = emptyList(),
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val hasDefaultDestination: Boolean = false,
    val effectiveFileLimitBytes: Long = HARD_MAX_FILE_BYTES,
) {
    val canConfirmSend: Boolean
        get() = selection.isNotEmpty() && selectedSessionId != null && !isSubmitting
}

sealed interface FileAction {
    data object PickFiles : FileAction
    data class SelectionReceived(val items: List<FileDraftItem>) : FileAction
    data class SharedSelectionReceived(val items: List<FileDraftItem>) : FileAction
    data class SelectionRejected(val count: Int) : FileAction
    data class RemoveDraftItem(val draftId: FileDraftId) : FileAction
    data object ClearDraft : FileAction
    data class RecipientSelected(val sessionId: BrowserSessionId) : FileAction
    data object ConfirmSend : FileAction
    data class ApproveIncoming(val transferId: FileTransferId) : FileAction
    data class ChangeIncomingDestination(val transferId: FileTransferId) : FileAction
    data class DestinationSelected(
        val transferId: FileTransferId,
        val destinationId: FileDestinationId,
    ) : FileAction
    data class DestinationCancelled(val transferId: FileTransferId) : FileAction
    data class DestinationUnavailable(val transferId: FileTransferId) : FileAction
    data class Cancel(val transferId: FileTransferId) : FileAction
    data class Retry(val transferId: FileTransferId) : FileAction
    data class Open(val transferId: FileTransferId) : FileAction
    data class OpenFailed(val message: String) : FileAction
    data object DismissFeedback : FileAction
}

sealed interface FileEffect {
    data object ChooseFiles : FileEffect
    data class ChooseDestination(val transferId: FileTransferId) : FileEffect
    data class UseDefaultDestination(
        val transferId: FileTransferId,
        val uri: String,
    ) : FileEffect
    data class OpenCompleted(val transferId: FileTransferId) : FileEffect
}
