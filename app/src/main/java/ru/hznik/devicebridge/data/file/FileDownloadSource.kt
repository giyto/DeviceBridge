package ru.hznik.devicebridge.data.file

import java.io.InputStream
import ru.hznik.devicebridge.domain.file.FileTransferMetadata

interface FileDownloadSource {
    fun inputStream(): InputStream

    suspend fun close()
}

fun interface FileDownloadSourceFactory {
    suspend fun create(metadata: FileTransferMetadata): FileDownloadSource
}
