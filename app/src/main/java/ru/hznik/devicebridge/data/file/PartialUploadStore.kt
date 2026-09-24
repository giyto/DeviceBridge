package ru.hznik.devicebridge.data.file

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.hznik.devicebridge.data.persistence.room.PartialUploadDao
import ru.hznik.devicebridge.data.persistence.room.PartialUploadEntity

/** Uploads smaller than this restart from zero instead of leaving a partial in the folder. */
const val RESUMABLE_UPLOAD_MIN_BYTES = 8L * 1024 * 1024

/** A retained partial is deleted when it has not changed for this long. */
const val PARTIAL_UPLOAD_TTL_MILLIS = 24L * 60 * 60 * 1000

/** The same file offered into the same folder continues the same partial. */
data class PartialUploadKey(
    val sha256: String,
    val sizeBytes: Long,
    val displayName: String,
    val treeUri: String,
)

data class PartialUploadRecord(
    val key: PartialUploadKey,
    val documentUri: String,
    val mimeType: String,
    val bytesRetained: Long,
    val updatedAtEpochMillis: Long,
)

data class PartialUploadSummary(
    val count: Int,
    val totalBytes: Long,
)

interface PartialUploadStore {
    val summary: Flow<PartialUploadSummary>

    /** The retained partial for [key] whose document still exists, or null. */
    suspend fun find(key: PartialUploadKey): PartialUploadRecord?

    suspend fun save(record: PartialUploadRecord)

    /** Forgets the record of [documentUri] without touching the document. */
    suspend fun forget(documentUri: String)

    /** Deletes the document of [documentUri] and its record. */
    suspend fun discard(documentUri: String)

    /** Drops expired partials (document and record) and records whose document is gone. */
    suspend fun cleanup(nowEpochMillis: Long): Int

    /** Deletes every retained partial; completed files and history are not touched. */
    suspend fun discardAll(): Int
}

class RoomPartialUploadStore(
    private val dao: PartialUploadDao,
    private val documents: PartialDocumentProvider,
    private val ttlMillis: Long = PARTIAL_UPLOAD_TTL_MILLIS,
) : PartialUploadStore {
    override val summary: Flow<PartialUploadSummary> = dao.observeAll().map { rows ->
        PartialUploadSummary(count = rows.size, totalBytes = rows.sumOf { it.bytesRetained })
    }

    override suspend fun find(key: PartialUploadKey): PartialUploadRecord? {
        val row = dao.find(key.sha256.lowercase(), key.sizeBytes, key.displayName, key.treeUri)
            ?: return null
        if (documents.size(row.partialDocumentUri) == null) {
            dao.delete(row.partialDocumentUri)
            return null
        }
        return row.toRecord()
    }

    override suspend fun save(record: PartialUploadRecord) {
        dao.upsert(record.toEntity())
    }

    override suspend fun forget(documentUri: String) {
        dao.delete(documentUri)
    }

    override suspend fun discard(documentUri: String) {
        documents.delete(documentUri)
        dao.delete(documentUri)
    }

    override suspend fun cleanup(nowEpochMillis: Long): Int {
        var removed = 0
        for (row in dao.getAll()) {
            val expired = nowEpochMillis - row.updatedAtEpochMillis >= ttlMillis
            when {
                expired -> {
                    documents.delete(row.partialDocumentUri)
                    removed += dao.delete(row.partialDocumentUri)
                }
                documents.size(row.partialDocumentUri) == null ->
                    removed += dao.delete(row.partialDocumentUri)
            }
        }
        return removed
    }

    override suspend fun discardAll(): Int {
        var removed = 0
        for (row in dao.getAll()) {
            documents.delete(row.partialDocumentUri)
            removed += dao.delete(row.partialDocumentUri)
        }
        return removed
    }

    private fun PartialUploadEntity.toRecord() = PartialUploadRecord(
        key = PartialUploadKey(sha256, sizeBytes, displayName, treeUri),
        documentUri = partialDocumentUri,
        mimeType = mimeType,
        bytesRetained = bytesRetained,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun PartialUploadRecord.toEntity() = PartialUploadEntity(
        partialDocumentUri = documentUri,
        sha256 = key.sha256.lowercase(),
        sizeBytes = key.sizeBytes,
        displayName = key.displayName,
        mimeType = mimeType,
        treeUri = key.treeUri,
        bytesRetained = bytesRetained,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}
