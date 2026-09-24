package ru.hznik.devicebridge.data.persistence.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PartialUploadDao {
    /** One partial per file and folder: a newer retain replaces the older record. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(partial: PartialUploadEntity)

    @Query(
        "SELECT * FROM partial_uploads WHERE sha256 = :sha256 AND size_bytes = :sizeBytes " +
            "AND display_name = :displayName AND tree_uri = :treeUri LIMIT 1",
    )
    suspend fun find(
        sha256: String,
        sizeBytes: Long,
        displayName: String,
        treeUri: String,
    ): PartialUploadEntity?

    @Query("SELECT * FROM partial_uploads")
    suspend fun getAll(): List<PartialUploadEntity>

    @Query("SELECT * FROM partial_uploads ORDER BY updated_at_epoch_millis DESC")
    fun observeAll(): Flow<List<PartialUploadEntity>>

    @Query("DELETE FROM partial_uploads WHERE partial_document_uri = :partialDocumentUri")
    suspend fun delete(partialDocumentUri: String): Int
}
