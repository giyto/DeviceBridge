package ru.hznik.devicebridge.data.persistence.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: HistoryRecordEntity): Long

    @Query("SELECT * FROM history_records ORDER BY timestamp_epoch_millis DESC, record_id DESC")
    fun observeAll(): Flow<List<HistoryRecordEntity>>

    @Query("DELETE FROM history_records WHERE record_id = :recordId")
    suspend fun deleteById(recordId: String): Int

    @Query("DELETE FROM history_records")
    suspend fun clear(): Int

    @Query("DELETE FROM history_records WHERE timestamp_epoch_millis < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int
}
