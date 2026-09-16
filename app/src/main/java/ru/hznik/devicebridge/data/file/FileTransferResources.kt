package ru.hznik.devicebridge.data.file

interface FileTransferResources {
    suspend fun cancelJob()

    suspend fun closeStreams()

    suspend fun cleanupPartial()
}
