package ru.hznik.devicebridge.data.history

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.data.text.TextTerminalHistoryRecorder
import ru.hznik.devicebridge.domain.history.HistoryDirection
import ru.hznik.devicebridge.domain.history.HistoryKind
import ru.hznik.devicebridge.domain.history.HistoryOperationId
import ru.hznik.devicebridge.domain.history.HistoryPersistenceEvent
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryRecordId
import ru.hznik.devicebridge.domain.history.HistoryStatus
import ru.hznik.devicebridge.domain.history.createHistoryTextPreview
import ru.hznik.devicebridge.domain.repository.HistoryRepository
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextTransferDirection
import ru.hznik.devicebridge.domain.text.TextTransferItem
import ru.hznik.devicebridge.domain.text.TextTransferStatus

class TextTransferHistoryRecorder(
    private val repository: HistoryRepository,
    private val applicationScope: CoroutineScope,
    private val failureReporter: HistoryPersistenceFailureReporter =
        HistoryPersistenceFailureReporter { },
    private val newRecordId: () -> HistoryRecordId = {
        HistoryRecordId(UUID.randomUUID().toString())
    },
) : TextTerminalHistoryRecorder {
    override suspend fun recordTerminal(item: TextTransferItem) {
        val status = when (item.status) {
            TextTransferStatus.DELIVERED -> HistoryStatus.DELIVERED
            TextTransferStatus.FAILED -> HistoryStatus.FAILED
            TextTransferStatus.PENDING,
            TextTransferStatus.SENDING,
            -> return
        }
        val record = HistoryRecord(
                id = newRecordId(),
                operationId = HistoryOperationId(item.stableHistoryOperationId()),
                kind = when (item.contentKind) {
                    TextContentKind.TEXT -> HistoryKind.TEXT
                    TextContentKind.LINK -> HistoryKind.LINK
                },
                direction = when (item.direction) {
                    TextTransferDirection.ANDROID_TO_BROWSER ->
                        HistoryDirection.ANDROID_TO_BROWSER
                    TextTransferDirection.BROWSER_TO_ANDROID ->
                        HistoryDirection.BROWSER_TO_ANDROID
                },
                browserLabel = item.browserLabel,
                timestampEpochMillis = item.updatedAtEpochMillis,
                status = status,
                textPreview = createHistoryTextPreview(item.content),
                file = null,
                failureReason = item.failureReason?.name?.lowercase(),
        )
        applicationScope.launch {
            try {
                repository.insert(record)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                failureReporter.report(
                    HistoryPersistenceEvent.WriteFailed(record.kind),
                )
            }
        }
    }

    private fun TextTransferItem.stableHistoryOperationId(): String {
        val source = buildString {
            append(generationId.value)
            append(':')
            append(sessionId.value)
            append(':')
            append(id.value)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
