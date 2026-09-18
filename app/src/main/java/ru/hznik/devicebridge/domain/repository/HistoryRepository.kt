package ru.hznik.devicebridge.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.hznik.devicebridge.domain.history.HistoryFilter
import ru.hznik.devicebridge.domain.history.HistoryInsertResult
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryRecordId

interface HistoryRepository {
    fun observe(filter: HistoryFilter = HistoryFilter()): Flow<List<HistoryRecord>>

    suspend fun insert(record: HistoryRecord): HistoryInsertResult

    suspend fun delete(recordId: HistoryRecordId): Boolean

    suspend fun clear(): Int

    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int
}
