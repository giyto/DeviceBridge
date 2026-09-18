package ru.hznik.devicebridge.data.persistence.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrustedBrowserDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(browser: TrustedBrowserEntity)

    @Query("SELECT * FROM trusted_browsers ORDER BY created_at_epoch_millis DESC")
    fun observeAll(): Flow<List<TrustedBrowserEntity>>

    @Query("SELECT * FROM trusted_browsers WHERE trusted_browser_id = :trustedBrowserId LIMIT 1")
    suspend fun findById(trustedBrowserId: String): TrustedBrowserEntity?

    @Query("SELECT * FROM trusted_browsers")
    suspend fun getAll(): List<TrustedBrowserEntity>

    @Query(
        "UPDATE trusted_browsers SET last_used_at_epoch_millis = :lastUsedAtEpochMillis " +
            "WHERE trusted_browser_id = :trustedBrowserId",
    )
    suspend fun updateLastUsed(
        trustedBrowserId: String,
        lastUsedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM trusted_browsers WHERE trusted_browser_id = :trustedBrowserId")
    suspend fun deleteById(trustedBrowserId: String): Int

    @Query("DELETE FROM trusted_browsers")
    suspend fun clear(): Int

    @Query("DELETE FROM trusted_browsers WHERE expires_at_epoch_millis <= :nowEpochMillis")
    suspend fun deleteExpired(nowEpochMillis: Long): Int
}
