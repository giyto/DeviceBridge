package ru.hznik.devicebridge.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.file.VerifyFileTransferRequest

interface FileTransferRepository {
    val state: StateFlow<FileTransferSnapshot>

    suspend fun create(request: CreateFileTransfersRequest): FileTransferOperationResult

    suspend fun approve(
        transferId: FileTransferId,
        destinationId: FileDestinationId?,
    ): FileTransferOperationResult

    suspend fun cancel(transferId: FileTransferId): FileTransferOperationResult

    suspend fun retry(transferId: FileTransferId): FileTransferOperationResult

    suspend fun verify(request: VerifyFileTransferRequest): FileTransferOperationResult
}
