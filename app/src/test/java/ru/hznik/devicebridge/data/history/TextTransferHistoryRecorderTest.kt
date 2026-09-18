package ru.hznik.devicebridge.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.hznik.devicebridge.domain.history.HistoryFilter
import ru.hznik.devicebridge.domain.history.HistoryInsertResult
import ru.hznik.devicebridge.domain.history.HistoryKind
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryRecordId
import ru.hznik.devicebridge.domain.history.HistoryStatus
import ru.hznik.devicebridge.domain.repository.HistoryRepository
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferFailureReason
import ru.hznik.devicebridge.domain.text.TextTransferItem
import ru.hznik.devicebridge.domain.text.TextTransferStatus

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TextTransferHistoryRecorderTest {
    @Test
    fun deliveredTextStoresOnlyBoundedPreviewAndSafeMetadata() = runTest {
        val repository = RecordingHistoryRepository()
        val recorder = TextTransferHistoryRecorder(
            repository = repository,
            applicationScope = backgroundScope,
            newRecordId = { HistoryRecordId("history-1") },
        )
        val payload = "🙂".repeat(250) + "private tail"
        val item = TextTransferItem.incoming(
            id = TextMessageId("message-1"),
            generationId = ServerGenerationId(1),
            sessionId = BrowserSessionId("session-1"),
            browserLabel = "Yandex Browser",
            content = payload,
            contentKind = TextContentKind.TEXT,
            receivedAtEpochMillis = 1_000,
        )

        recorder.recordTerminal(item)
        runCurrent()

        val record = repository.records.single()
        assertEquals(HistoryKind.TEXT, record.kind)
        assertEquals(HistoryStatus.DELIVERED, record.status)
        assertEquals(200, requireNotNull(record.textPreview).codePointCount(0, record.textPreview.length))
        assertNull(record.file)
        assertEquals(false, record.textPreview == payload)
    }

    @Test
    fun failedLinkStoresSafeReasonWithoutChangingPayloadShape() = runTest {
        val repository = RecordingHistoryRepository()
        val recorder = TextTransferHistoryRecorder(
            repository = repository,
            applicationScope = backgroundScope,
            newRecordId = { HistoryRecordId("history-2") },
        )
        val failed = TextTransferItem.outgoing(
            id = TextMessageId("message-2"),
            generationId = ServerGenerationId(1),
            sessionId = BrowserSessionId("session-1"),
            browserLabel = "Edge",
            content = "https://example.com",
            contentKind = TextContentKind.LINK,
            createdAtEpochMillis = 1_000,
        ).transitionTo(
            next = TextTransferStatus.SENDING,
            changedAtEpochMillis = 1_001,
        ).transitionTo(
            next = TextTransferStatus.FAILED,
            changedAtEpochMillis = 1_002,
            failureReason = TextTransferFailureReason.CONNECTION_LOST,
        )

        recorder.recordTerminal(failed)
        runCurrent()

        val record = repository.records.single()
        assertEquals(HistoryKind.LINK, record.kind)
        assertEquals(HistoryStatus.FAILED, record.status)
        assertEquals("connection_lost", record.failureReason)
    }

    private class RecordingHistoryRepository : HistoryRepository {
        val records = mutableListOf<HistoryRecord>()
        private val state = MutableStateFlow<List<HistoryRecord>>(emptyList())

        override fun observe(filter: HistoryFilter): Flow<List<HistoryRecord>> = state

        override suspend fun insert(record: HistoryRecord): HistoryInsertResult {
            records += record
            state.value = records.toList()
            return HistoryInsertResult.Inserted
        }

        override suspend fun delete(recordId: HistoryRecordId): Boolean = false
        override suspend fun clear(): Int = 0
        override suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int = 0
    }
}
