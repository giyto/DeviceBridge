package ru.hznik.devicebridge.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.hznik.devicebridge.domain.history.HistoryFilter
import ru.hznik.devicebridge.domain.history.HistoryInsertResult
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryRecordId

interface HistoryRepository {
    fun observe(filter: HistoryFilter = HistoryFilter()): Flow<List<HistoryRecord>>

    suspend fun insert(record: HistoryRecord): HistoryInsertResult

    /**
     * Records a newer result of an operation that may already have one, e.g. a transfer that
     * failed and was then continued or retried: the newer record supersedes the older.
     */
    suspend fun replace(record: HistoryRecord): HistoryInsertResult = insert(record)

    suspend fun delete(recordId: HistoryRecordId): Boolean

    suspend fun clear(): Int
}
