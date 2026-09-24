package ru.hznik.devicebridge.data.file

interface FileTransferResources {
    suspend fun cancelJob()

    suspend fun closeStreams()

    /**
     * Ends the partial output of an unfinished transfer. With [retain] the written part is kept
     * for a later resume when the target supports it; otherwise it is deleted.
     */
    suspend fun cleanupPartial(retain: Boolean = false)
}
