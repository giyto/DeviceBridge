package ru.hznik.devicebridge.domain.usecase

import kotlinx.coroutines.flow.StateFlow
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.file.VerifyFileTransferRequest
import ru.hznik.devicebridge.domain.repository.FileTransferRepository

class ObserveFileTransfersUseCase(
    private val repository: FileTransferRepository,
) {
    operator fun invoke(): StateFlow<FileTransferSnapshot> = repository.state
}

class CreateFileTransfersUseCase(
    private val repository: FileTransferRepository,
) {
    suspend operator fun invoke(request: CreateFileTransfersRequest): FileTransferOperationResult =
        repository.create(request)
}

class ApproveFileTransferUseCase(
    private val repository: FileTransferRepository,
) {
    suspend operator fun invoke(
        transferId: FileTransferId,
        destinationId: FileDestinationId?,
    ): FileTransferOperationResult = repository.approve(transferId, destinationId)
}

class CancelFileTransferUseCase(
    private val repository: FileTransferRepository,
) {
    suspend operator fun invoke(transferId: FileTransferId): FileTransferOperationResult =
        repository.cancel(transferId)
}

class RetryFileTransferUseCase(
    private val repository: FileTransferRepository,
) {
    suspend operator fun invoke(transferId: FileTransferId): FileTransferOperationResult =
        repository.retry(transferId)
}

class VerifyFileTransferUseCase(
    private val repository: FileTransferRepository,
) {
    suspend operator fun invoke(request: VerifyFileTransferRequest): FileTransferOperationResult =
        repository.verify(request)
}
