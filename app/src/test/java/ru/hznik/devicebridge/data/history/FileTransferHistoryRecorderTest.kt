package ru.hznik.devicebridge.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.FileTransferState
import ru.hznik.devicebridge.domain.history.HistoryFilter
import ru.hznik.devicebridge.domain.history.HistoryInsertResult
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryRecordId
import ru.hznik.devicebridge.domain.history.HistoryStatus
import ru.hznik.devicebridge.domain.repository.HistoryRepository
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FileTransferHistoryRecorderTest {
    @Test
    fun terminalFileStatesStoreMetadataButNoSourceLocation() = runTest {
        val repository = RecordingHistoryRepository()
        val recorder = FileTransferHistoryRecorder(
            repository = repository,
            browserLabelFor = { "Yandex Browser" },
            applicationScope = backgroundScope,
            nowEpochMillis = { 5_000 },
            newRecordId = { HistoryRecordId("history-1") },
        )

        recorder.recordTerminal(state(FileTransferPhase.COMPLETED))
        recorder.recordTerminal(state(FileTransferPhase.CANCELLED))
        recorder.recordTerminal(state(FileTransferPhase.FAILED))
        runCurrent()

        assertEquals(
            listOf(
                HistoryStatus.COMPLETED,
                HistoryStatus.CANCELLED,
                HistoryStatus.FAILED,
            ),
            repository.records.map(HistoryRecord::status),
        )
        repository.records.forEach { record ->
            assertEquals("video.mp4", requireNotNull(record.file).displayName)
            assertEquals("b".repeat(64), record.file.sha256)
            assertNull(record.textPreview)
            assertEquals("Yandex Browser", record.browserLabel)
        }
        assertEquals("stream_failed", repository.records.last().failureReason)
    }

    private fun state(phase: FileTransferPhase): FileTransferState {
        val metadata = FileTransferMetadata(
            id = FileTransferId("transfer-1-" + phase.name.lowercase()),
            displayName = "video.mp4",
            sizeBytes = 42,
            mimeType = "video/mp4",
            sha256 = "b".repeat(64),
            direction = FileTransferDirection.BROWSER_TO_ANDROID,
        )
        return FileTransferState.queued(
            generationId = ServerGenerationId(1),
            ownerSessionId = BrowserSessionId("session-1"),
            metadata = metadata,
        ).evolve(
            phase = phase,
            bytesTransferred = if (phase == FileTransferPhase.COMPLETED) 42 else 0,
            failure = if (phase == FileTransferPhase.FAILED) {
                FileTransferFailure.StreamFailed
            } else {
                null
            },
        )
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
