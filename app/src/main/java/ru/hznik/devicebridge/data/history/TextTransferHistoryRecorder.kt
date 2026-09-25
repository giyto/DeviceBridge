package ru.hznik.devicebridge.data.history

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import ru.hznik.devicebridge.data.text.TextTerminalHistoryRecorder
import ru.hznik.devicebridge.domain.history.HistoryDirection
import ru.hznik.devicebridge.domain.history.HistoryKind
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
                operationId = historyOperationId(item.generationId, item.sessionId, item.id.value),
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
        applicationScope.persistBestEffort(record, failureReporter) {
            repository.insert(it)
        }
    }
}
