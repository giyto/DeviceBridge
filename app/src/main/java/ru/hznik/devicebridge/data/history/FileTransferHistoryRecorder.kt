package ru.hznik.devicebridge.data.history

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.data.file.FileTerminalHistoryRecorder
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.FileTransferState
import ru.hznik.devicebridge.domain.history.HistoryDirection
import ru.hznik.devicebridge.domain.history.HistoryFileMetadata
import ru.hznik.devicebridge.domain.history.HistoryKind
import ru.hznik.devicebridge.domain.history.HistoryOperationId
import ru.hznik.devicebridge.domain.history.HistoryPersistenceEvent
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryRecordId
import ru.hznik.devicebridge.domain.history.HistoryStatus
import ru.hznik.devicebridge.domain.repository.HistoryRepository
import ru.hznik.devicebridge.domain.session.BrowserSessionId

class FileTransferHistoryRecorder(
    private val repository: HistoryRepository,
    private val browserLabelFor: (BrowserSessionId) -> String?,
    private val applicationScope: CoroutineScope,
    private val failureReporter: HistoryPersistenceFailureReporter =
        HistoryPersistenceFailureReporter { },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val newRecordId: () -> HistoryRecordId = {
        HistoryRecordId(UUID.randomUUID().toString())
    },
) : FileTerminalHistoryRecorder {
    override suspend fun recordTerminal(item: FileTransferState) {
        val status = when (item.phase) {
            FileTransferPhase.COMPLETED -> HistoryStatus.COMPLETED
            FileTransferPhase.CANCELLED -> HistoryStatus.CANCELLED
            FileTransferPhase.FAILED -> HistoryStatus.FAILED
            else -> return
        }
        val record = HistoryRecord(
                id = newRecordId(),
                operationId = HistoryOperationId(item.stableHistoryOperationId()),
                kind = HistoryKind.FILE,
                direction = when (item.metadata.direction) {
                    FileTransferDirection.ANDROID_TO_BROWSER ->
                        HistoryDirection.ANDROID_TO_BROWSER
                    FileTransferDirection.BROWSER_TO_ANDROID ->
                        HistoryDirection.BROWSER_TO_ANDROID
                },
                browserLabel = browserLabelFor(item.ownerSessionId) ?: "Browser",
                timestampEpochMillis = nowEpochMillis(),
                status = status,
                textPreview = null,
                file = HistoryFileMetadata(
                    displayName = item.metadata.displayName,
                    sizeBytes = item.metadata.sizeBytes,
                    mimeType = item.metadata.mimeType,
                    sha256 = item.metadata.sha256,
                ),
                failureReason = item.failure?.toSafeHistoryReason(),
        )
        applicationScope.launch {
            try {
                repository.insert(record)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                failureReporter.report(
                    HistoryPersistenceEvent.WriteFailed(HistoryKind.FILE),
                )
            }
        }
    }

    private fun FileTransferState.stableHistoryOperationId(): String {
        val source = buildString {
            append(generationId.value)
            append(':')
            append(ownerSessionId.value)
            append(':')
            append(metadata.id.value)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

private fun FileTransferFailure.toSafeHistoryReason(): String = when (this) {
    FileTransferFailure.ChecksumMismatch -> "checksum_mismatch"
    FileTransferFailure.StreamFailed -> "stream_failed"
    FileTransferFailure.SessionUnavailable -> "session_unavailable"
    FileTransferFailure.StorageUnavailable -> "storage_unavailable"
    FileTransferFailure.InsufficientSpace -> "insufficient_space"
    FileTransferFailure.CapacityReached -> "capacity_reached"
    FileTransferFailure.FileLimitExceeded -> "file_limit_exceeded"
    FileTransferFailure.SourceUnavailable -> "source_unavailable"
    FileTransferFailure.ProtocolMismatch -> "protocol_mismatch"
}
