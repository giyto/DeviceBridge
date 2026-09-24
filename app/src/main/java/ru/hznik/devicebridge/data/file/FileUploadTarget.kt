package ru.hznik.devicebridge.data.file

import java.io.OutputStream
import java.security.MessageDigest
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata

interface FileUploadTarget {
    fun outputStream(): OutputStream

    /** Bytes of the file already stored; the request body continues from here. */
    val offsetBytes: Long
        get() = 0

    /** SHA-256 state already fed with the first [offsetBytes] bytes of the file. */
    fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    suspend fun commit()

    suspend fun abort()

    /**
     * Keeps what was written so the same file can continue later. Targets that cannot resume
     * delete their output instead.
     */
    suspend fun retain() = abort()

    suspend fun close()
}

fun interface FileUploadTargetFactory {
    suspend fun create(
        destinationId: FileDestinationId,
        metadata: FileTransferMetadata,
    ): FileUploadTarget

    /** With [resume], a retained partial of the same file in the same folder is continued. */
    suspend fun create(
        destinationId: FileDestinationId,
        metadata: FileTransferMetadata,
        resume: Boolean,
    ): FileUploadTarget = create(destinationId, metadata)
}
