package ru.hznik.devicebridge.data.file

import java.io.OutputStream
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata

interface FileUploadTarget {
    fun outputStream(): OutputStream

    suspend fun commit()

    suspend fun abort()

    suspend fun close()
}

fun interface FileUploadTargetFactory {
    suspend fun create(
        destinationId: FileDestinationId,
        metadata: FileTransferMetadata,
    ): FileUploadTarget
}
