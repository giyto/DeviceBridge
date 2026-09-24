package ru.hznik.devicebridge.feature.file

import kotlin.math.roundToInt
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
    val senderLabel: String? = null,
    val autoAccepted: Boolean = false,
    val autoAcceptPaused: Boolean = false,
    /** A failed item can continue from these bytes instead of starting over. */
    val resumableBytes: Long? = null,
    /** The running attempt continues after this many bytes kept by an earlier one. */
    val resumedFromBytes: Long = 0,
) {
    val progress: Float
        get() = if (sizeBytes == 0L) {
            if (phase == FileTransferPhase.COMPLETED) 1f else 0f
        } else {
            (bytesTransferred.toDouble() / sizeBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }
    val canCancel: Boolean get() = !phase.isTerminal
    val hasActiveProgress: Boolean
        get() = phase == FileTransferPhase.CONNECTING ||
            phase == FileTransferPhase.TRANSFERRING ||
            phase == FileTransferPhase.VERIFYING
    val hasDeterminateProgress: Boolean
        get() = phase == FileTransferPhase.TRANSFERRING && sizeBytes > 0
    val progressPercent: Int?
        get() = progress.takeIf { hasDeterminateProgress }?.times(100)?.roundToInt()
    val canRetry: Boolean get() = phase == FileTransferPhase.FAILED || phase == FileTransferPhase.CANCELLED
    /** Retry continues an upload from the part kept on the phone. */
    val continuesUpload: Boolean
        get() = phase == FileTransferPhase.FAILED &&
            direction == FileTransferDirection.BROWSER_TO_ANDROID &&
            resumableBytes != null
    val canOpen: Boolean get() = phase == FileTransferPhase.COMPLETED && direction == FileTransferDirection.BROWSER_TO_ANDROID
    val awaitsApproval: Boolean get() = direction == FileTransferDirection.BROWSER_TO_ANDROID && phase == FileTransferPhase.CONNECTING
    val failureMessage: String? get() = resumableFailureMessage() ?: when (failure) {
        FileTransferFailure.ChecksumMismatch ->
            "Контрольная сумма не совпала. Повторите передачу."
        FileTransferFailure.StreamFailed ->
            "Передача прервалась. Незавершённый файл удалён, если хранилище это разрешило."
        FileTransferFailure.SessionUnavailable ->
            "Браузер отключён. Подключите его снова и повторите передачу."
        FileTransferFailure.StorageUnavailable ->
            "Выбранная папка недоступна. Выберите другую папку."
        FileTransferFailure.InsufficientSpace ->
            "На устройстве недостаточно свободного места."
        FileTransferFailure.CapacityReached ->
            "Очередь передач заполнена. Завершите или отмените другие файлы."
        FileTransferFailure.FileLimitExceeded ->
            "Файл превышает допустимый размер. Выберите другой файл."
        FileTransferFailure.SourceUnavailable ->
            "Исходный файл больше недоступен или был изменён. Выберите его заново."
        FileTransferFailure.ProtocolMismatch ->
            "Версия протокола не поддерживается. Обновите DeviceBridge."
        null -> null
    }
}

private fun FileTransferItemUiState.resumableFailureMessage(): String? {
    val kept = resumableBytes ?: return null
    if (phase != FileTransferPhase.FAILED) return null
    return if (direction == FileTransferDirection.ANDROID_TO_BROWSER) {
        "Загрузка прервалась. Браузер может возобновить её в течение 15 минут, иначе повторите с начала."
    } else if (failure == FileTransferFailure.InsufficientSpace) {
        "На устройстве недостаточно свободного места. Сохранено ${formatBytes(kept)} — освободите место и продолжите."
    } else {
        "Передача прервалась. Сохранено ${formatBytes(kept)} из ${formatBytes(sizeBytes)}, её можно продолжить."
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
