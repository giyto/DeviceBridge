package ru.hznik.devicebridge.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.hznik.devicebridge.domain.history.HistoryFilter
import ru.hznik.devicebridge.domain.history.HistoryInsertResult
import ru.hznik.devicebridge.domain.history.HistoryKind
import ru.hznik.devicebridge.domain.history.HistoryPersistenceEvent
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryRecordId
import ru.hznik.devicebridge.domain.repository.HistoryRepository
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferItem

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryPersistenceFailureTest {
    @Test
    fun failedWritePublishesOnlySafeKindAndDoesNotEscapeAsyncRecorder() = runTest {
        val events = mutableListOf<HistoryPersistenceEvent>()
        val recorder = TextTransferHistoryRecorder(
            repository = FailingHistoryRepository(),
            applicationScope = backgroundScope,
            failureReporter = HistoryPersistenceFailureReporter { event ->
                events += event
            },
            newRecordId = { HistoryRecordId("history-1") },
        )
        val item = TextTransferItem.incoming(
            id = TextMessageId("message-1"),
            generationId = ServerGenerationId(1),
            sessionId = BrowserSessionId("session-1"),
            browserLabel = "Chrome",
            content = "private payload",
            contentKind = TextContentKind.TEXT,
            receivedAtEpochMillis = 1_000,
        )

        recorder.recordTerminal(item)
        runCurrent()

        assertEquals(
            listOf(HistoryPersistenceEvent.WriteFailed(HistoryKind.TEXT)),
            events,
        )
        val fields = HistoryPersistenceEvent.WriteFailed::class.java.declaredFields
            .map { it.name.lowercase() }
        assertFalse(fields.any { it.contains("payload") || it.contains("cause") })
    }

    private class FailingHistoryRepository : HistoryRepository {
        override fun observe(filter: HistoryFilter): Flow<List<HistoryRecord>> = emptyFlow()
        override suspend fun insert(record: HistoryRecord): HistoryInsertResult =
            error("database path and private payload must not escape")
        override suspend fun delete(recordId: HistoryRecordId): Boolean = false
        override suspend fun clear(): Int = 0
        override suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int = 0
    }
}
