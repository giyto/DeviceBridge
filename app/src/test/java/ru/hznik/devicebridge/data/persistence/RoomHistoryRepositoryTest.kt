package ru.hznik.devicebridge.data.persistence

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.persistence.room.HistoryDao
import ru.hznik.devicebridge.data.persistence.room.HistoryRecordEntity
import ru.hznik.devicebridge.data.persistence.room.RoomHistoryRepository
import ru.hznik.devicebridge.domain.history.HistoryDirection
import ru.hznik.devicebridge.domain.history.HistoryInsertResult
import ru.hznik.devicebridge.domain.history.HistoryKind
import ru.hznik.devicebridge.domain.history.HistoryOperationId
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryRecordId
import ru.hznik.devicebridge.domain.history.HistoryStatus
import ru.hznik.devicebridge.domain.repository.SettingsRepository
import ru.hznik.devicebridge.domain.settings.DestinationTree
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.settings.SettingsUpdateResult

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoomHistoryRepositoryTest {
    private val now = Instant.parse("2026-09-17T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun repeatedOperationAndKindIsIdempotent() = runTest {
        val dao = FakeHistoryDao()
        val repository = repository(dao)
        val first = record("record-1", "operation-1", now.toEpochMilli())
        val duplicate = record("record-2", "operation-1", now.toEpochMilli())

        assertEquals(HistoryInsertResult.Inserted, repository.insert(first))
        assertEquals(HistoryInsertResult.AlreadyRecorded, repository.insert(duplicate))
        assertEquals(1, repository.observe().first().size)
    }

    @Test
    fun aLaterResultOfTheSameOperationReplacesTheEarlierOne() = runTest {
        val dao = FakeHistoryDao()
        val repository = repository(dao)
        repository.insert(record("record-1", "operation-1", now.toEpochMilli(), HistoryStatus.FAILED))

        assertEquals(
            HistoryInsertResult.Inserted,
            repository.replace(record("record-2", "operation-1", now.toEpochMilli() + 1, HistoryStatus.DELIVERED)),
        )

        val records = repository.observe().first()
        assertEquals(listOf("record-2"), records.map { it.id.value })
        assertEquals(HistoryStatus.DELIVERED, records.single().status)
    }

    @Test
    fun cleanupRunsAtStartAndAfterSuccessfulInsert() = runTest {
        val cutoff = now.minusSeconds(30L * 24 * 60 * 60).toEpochMilli()
        val dao = FakeHistoryDao(
            record("old", "old-op", cutoff - 1).asEntity(),
            record("new", "new-op", cutoff).asEntity(),
        )
        val repository = repository(dao)

        runCurrent()
        assertEquals(listOf("new"), repository.observe().first().map { it.id.value })

        val stale = record("stale", "stale-op", cutoff - 2)
        assertEquals(HistoryInsertResult.Inserted, repository.insert(stale))
        assertEquals(listOf("new"), repository.observe().first().map { it.id.value })
    }

    @Test
    fun deleteAndClearOnlyMutateHistoryRows() = runTest {
        val dao = FakeHistoryDao()
        val repository = repository(dao)
        repository.insert(record("one", "op-one", now.toEpochMilli()))
        repository.insert(record("two", "op-two", now.toEpochMilli() + 1))

        assertTrue(repository.delete(HistoryRecordId("one")))
        assertFalse(repository.delete(HistoryRecordId("missing")))
        assertEquals(1, repository.clear())
        assertEquals(emptyList<HistoryRecord>(), repository.observe().first())
    }

    private fun kotlinx.coroutines.test.TestScope.repository(
        dao: FakeHistoryDao,
    ): RoomHistoryRepository = RoomHistoryRepository(
        dao = dao,
        settingsRepository = FakeSettingsRepository(),
        clock = clock,
        applicationScope = backgroundScope,
    )

    private fun record(
        id: String,
        operationId: String,
        timestamp: Long,
        status: HistoryStatus = HistoryStatus.DELIVERED,
    ) = HistoryRecord(
        id = HistoryRecordId(id),
        operationId = HistoryOperationId(operationId),
        kind = HistoryKind.TEXT,
        direction = HistoryDirection.ANDROID_TO_BROWSER,
        browserLabel = "Chrome",
        timestampEpochMillis = timestamp,
        status = status,
        textPreview = "preview",
        file = null,
        failureReason = null,
    )

    private fun HistoryRecord.asEntity() = HistoryRecordEntity(
        recordId = id.value,
        operationId = operationId.value,
        kind = "text",
        direction = "android_to_browser",
        browserLabel = browserLabel,
        timestampEpochMillis = timestampEpochMillis,
        terminalStatus = "delivered",
        textPreview = textPreview,
        fileDisplayName = null,
        fileSizeBytes = null,
        fileMimeType = null,
        fileSha256 = null,
        failureReason = null,
    )

    private class FakeHistoryDao(
        vararg initial: HistoryRecordEntity,
    ) : HistoryDao {
        private val rows = MutableStateFlow(initial.toList())

        override suspend fun insert(record: HistoryRecordEntity): Long {
            if (rows.value.any { it.operationId == record.operationId && it.kind == record.kind }) {
                return -1
            }
            rows.value = (rows.value + record).sortedByDescending { it.timestampEpochMillis }
            return rows.value.size.toLong()
        }

        override suspend fun replace(record: HistoryRecordEntity): Long {
            rows.value = (rows.value.filterNot { it.operationId == record.operationId && it.kind == record.kind } + record)
                .sortedByDescending { it.timestampEpochMillis }
            return rows.value.size.toLong()
        }

        override fun observeAll(): Flow<List<HistoryRecordEntity>> = rows

        override suspend fun deleteById(recordId: String): Int {
            val before = rows.value.size
            rows.value = rows.value.filterNot { it.recordId == recordId }
            return before - rows.value.size
        }

        override suspend fun clear(): Int {
            val count = rows.value.size
            rows.value = emptyList()
            return count
        }

        override suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int {
            val before = rows.value.size
            rows.value = rows.value.filterNot { it.timestampEpochMillis < cutoffEpochMillis }
            return before - rows.value.size
        }
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val current = MutableStateFlow(DeviceSettings.defaults())
        override val settings: Flow<DeviceSettings> = current

        override suspend fun updateDeviceName(value: String): SettingsUpdateResult =
            SettingsUpdateResult.Updated(current.value)

        override suspend fun updateRetentionDays(value: Int): SettingsUpdateResult =
            SettingsUpdateResult.Updated(current.value)

        override suspend fun updateDestinationTree(value: DestinationTree?): SettingsUpdateResult =
            SettingsUpdateResult.Updated(current.value)

        override suspend fun updateEffectiveFileLimitBytes(value: Long): SettingsUpdateResult =
            SettingsUpdateResult.Updated(current.value)

        override suspend fun updateAutoAcceptTrustedFiles(enabled: Boolean): SettingsUpdateResult =
            SettingsUpdateResult.Updated(current.value)

        override suspend fun updateIdleStopTimeout(
            value: ru.hznik.devicebridge.domain.settings.IdleStopTimeout,
        ): SettingsUpdateResult = SettingsUpdateResult.Updated(current.value)

        override suspend fun updateSecureMode(enabled: Boolean): SettingsUpdateResult =
            SettingsUpdateResult.Updated(current.value)

        override suspend fun updateNetworkName(value: String): SettingsUpdateResult =
            SettingsUpdateResult.Updated(current.value)
    }
}
