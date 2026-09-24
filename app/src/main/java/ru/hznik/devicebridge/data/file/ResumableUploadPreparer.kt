package ru.hznik.devicebridge.data.file

import java.io.Closeable
import java.io.OutputStream
import java.security.MessageDigest
import ru.hznik.devicebridge.domain.file.FileTransferMetadata

/** Byte access to documents in the destination folder. */
interface DocumentIo {
    /** Opens [documentUri] truncated, for writing from byte 0. */
    suspend fun openFresh(documentUri: String): OutputStream?

    /**
     * Opens an existing document for reading its current content and continuing it. Returns null
     * when the provider cannot give a seekable descriptor, so the upload restarts from zero.
     */
    suspend fun openForResume(documentUri: String): ResumableDocument?
}

interface ResumableDocument : Closeable {
    /** Feeds the whole current content to [consumer] in order and returns its length. */
    fun readPrefix(consumer: (ByteArray, Int) -> Unit): Long

    /** Truncates the document to [offset] bytes and returns a stream writing from there. */
    fun outputAt(offset: Long): OutputStream
}

/** The output of an approved Browser -> Android upload, ready for the body. */
class PreparedUpload(
    val handle: PartialDocumentHandle,
    val output: OutputStream,
    /** Bytes of the file already present; the body continues from here. */
    val offsetBytes: Long,
    /** SHA-256 state already updated with the first [offsetBytes] bytes. */
    val digest: MessageDigest,
    /** Set for files large enough to be kept for a later resume. */
    val resumeKey: PartialUploadKey?,
)

/**
 * Finds a retained partial of the same file in the same folder and continues it, rebuilding the
 * checksum from what is actually stored. Anything unusable is discarded and the upload restarts
 * from zero in a new partial document.
 */
class ResumableUploadPreparer(
    private val manager: PartialDocumentManager,
    private val store: PartialUploadStore,
    private val io: DocumentIo,
) {
    suspend fun prepare(
        treeUri: String,
        metadata: FileTransferMetadata,
        resume: Boolean,
    ): PreparedUpload {
        val key = if (metadata.sizeBytes >= RESUMABLE_UPLOAD_MIN_BYTES) {
            PartialUploadKey(
                sha256 = metadata.sha256.lowercase(),
                sizeBytes = metadata.sizeBytes,
                displayName = metadata.displayName,
                treeUri = treeUri,
            )
        } else {
            null
        }
        if (resume && key != null) {
            continueRetained(key, treeUri, metadata)?.let { return it }
        }
        val handle = manager.create(
            treeUri = treeUri,
            requestedName = metadata.displayName,
            fallbackId = metadata.id.value,
        )
        val output = io.openFresh(handle.documentUri) ?: run {
            manager.cleanup(handle)
            error("Destination provider did not open output")
        }
        return PreparedUpload(handle, output, 0, sha256(), key)
    }

    private suspend fun continueRetained(
        key: PartialUploadKey,
        treeUri: String,
        metadata: FileTransferMetadata,
    ): PreparedUpload? {
        val record = store.find(key) ?: return null
        val document = runCatching { io.openForResume(record.documentUri) }.getOrNull()
        if (document != null) {
            try {
                val digest = sha256()
                val length = document.readPrefix { buffer, count -> digest.update(buffer, 0, count) }
                if (length in 1..metadata.sizeBytes) {
                    val handle = manager.adopt(
                        treeUri = treeUri,
                        documentUri = record.documentUri,
                        requestedName = metadata.displayName,
                        fallbackId = metadata.id.value,
                    )
                    return PreparedUpload(handle, document.outputAt(length), length, digest, key)
                }
            } catch (_: Exception) {
                // An unreadable or oversized prefix cannot be trusted; start over below.
            }
            runCatching { document.close() }
        }
        store.discard(record.documentUri)
        return null
    }

    private fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")
}
