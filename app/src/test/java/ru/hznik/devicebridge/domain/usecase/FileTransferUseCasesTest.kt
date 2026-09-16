package ru.hznik.devicebridge.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileCommandId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.file.VerifyFileTransferRequest
import ru.hznik.devicebridge.domain.repository.FileTransferRepository
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId

class FileTransferUseCasesTest {

    @Test
    fun useCasesDelegateOnlyThroughRepositoryContract() = runTest {
        val repository = FakeFileTransferRepository()
        val create = createRequest()
        val transferId = FileTransferId("transfer-1")
        val destinationId = FileDestinationId("destination-1")
        val verification = VerifyFileTransferRequest(
            transferId = transferId,
            sizeBytes = 0,
            sha256 = "a".repeat(64),
        )

        assertSame(repository.state, ObserveFileTransfersUseCase(repository)())
        assertEquals(repository.result, CreateFileTransfersUseCase(repository)(create))
        assertEquals(
            repository.result,
            ApproveFileTransferUseCase(repository)(transferId, destinationId),
        )
        assertEquals(repository.result, CancelFileTransferUseCase(repository)(transferId))
        assertEquals(repository.result, RetryFileTransferUseCase(repository)(transferId))
        assertEquals(repository.result, VerifyFileTransferUseCase(repository)(verification))

        assertEquals(listOf(create), repository.created)
        assertEquals(listOf(transferId to destinationId), repository.approved)
        assertEquals(listOf(transferId), repository.cancelled)
        assertEquals(listOf(transferId), repository.retried)
        assertEquals(listOf(verification), repository.verified)
    }

    private class FakeFileTransferRepository : FileTransferRepository {
        override val state = MutableStateFlow(FileTransferSnapshot(emptyList()))
        val result = FileTransferOperationResult.Accepted
        val created = mutableListOf<CreateFileTransfersRequest>()
        val approved = mutableListOf<Pair<FileTransferId, FileDestinationId?>>()
        val cancelled = mutableListOf<FileTransferId>()
        val retried = mutableListOf<FileTransferId>()
        val verified = mutableListOf<VerifyFileTransferRequest>()

        override suspend fun create(request: CreateFileTransfersRequest) =
            result.also { created += request }

        override suspend fun approve(
            transferId: FileTransferId,
            destinationId: FileDestinationId?,
        ) = result.also { approved += transferId to destinationId }

        override suspend fun cancel(transferId: FileTransferId) =
            result.also { cancelled += transferId }

        override suspend fun retry(transferId: FileTransferId) =
            result.also { retried += transferId }

        override suspend fun verify(request: VerifyFileTransferRequest) =
            result.also { verified += request }
    }

    private fun createRequest() = CreateFileTransfersRequest(
        commandId = FileCommandId("offer-1"),
        generationId = ServerGenerationId(1),
        ownerSessionId = BrowserSessionId("session-1"),
        files = listOf(
            FileTransferMetadata(
                id = FileTransferId("transfer-1"),
                displayName = "empty.txt",
                sizeBytes = 0,
                mimeType = "text/plain",
                sha256 = "a".repeat(64),
                direction = FileTransferDirection.BROWSER_TO_ANDROID,
            ),
        ),
    )
}
