package ru.hznik.devicebridge.data.persistence.room

import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.domain.history.HistoryFilter
import ru.hznik.devicebridge.domain.history.HistoryInsertResult
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryRecordId
import ru.hznik.devicebridge.domain.repository.HistoryRepository
import ru.hznik.devicebridge.domain.repository.SettingsRepository

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000

class RoomHistoryRepository(
    private val dao: HistoryDao,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
    applicationScope: CoroutineScope,
) : HistoryRepository {
    init {
        applicationScope.launch {
            cleanupExpired()
        }
    }

    override fun observe(filter: HistoryFilter): Flow<List<HistoryRecord>> =
        dao.observeAll().map { entities ->
            entities
                .mapNotNull(HistoryRecordEntity::toDomainOrNull)
                .filter { record -> filter.matches(record) }
        }

    override suspend fun insert(record: HistoryRecord): HistoryInsertResult {
        val rowId = dao.insert(record.toEntity())
        if (rowId == -1L) {
            return HistoryInsertResult.AlreadyRecorded
        }
        cleanupExpired()
        return HistoryInsertResult.Inserted
    }

    override suspend fun replace(record: HistoryRecord): HistoryInsertResult {
        dao.replace(record.toEntity())
        cleanupExpired()
        return HistoryInsertResult.Inserted
    }

    override suspend fun delete(recordId: HistoryRecordId): Boolean =
        dao.deleteById(recordId.value) > 0

    override suspend fun clear(): Int = dao.clear()

    private suspend fun cleanupExpired(): Int {
        val retentionDays = settingsRepository.settings.first().retentionDays
        val cutoffEpochMillis = clock.millis() - retentionDays * MILLIS_PER_DAY
        return dao.deleteOlderThan(cutoffEpochMillis)
    }
}

private fun HistoryFilter.matches(record: HistoryRecord): Boolean =
    (directions.isEmpty() || record.direction in directions) &&
        (kinds.isEmpty() || record.kind in kinds) &&
        (statuses.isEmpty() || record.status in statuses)
