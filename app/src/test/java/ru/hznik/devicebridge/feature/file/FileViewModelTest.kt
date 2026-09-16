package ru.hznik.devicebridge.feature.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository
import ru.hznik.devicebridge.domain.repository.FileTransferRepository
import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingCodeState
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.usecase.ApproveFileTransferUseCase
import ru.hznik.devicebridge.domain.usecase.CancelFileTransferUseCase
import ru.hznik.devicebridge.domain.usecase.CreateFileTransfersUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveBrowserSessionsUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveFileTransfersUseCase
import ru.hznik.devicebridge.domain.usecase.RetryFileTransferUseCase

@OptIn(ExperimentalCoroutinesApi::class)
class FileViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun selectionBuildsPreviewAndConfirmedSendUsesSelectedRecipient() = runTest(dispatcher) {
        val sessions = FakeSessions(active(first))
        val files = FakeFiles()
        val viewModel = viewModel(sessions, files)
        runCurrent()

        viewModel.onAction(FileAction.SelectionReceived(listOf(candidate("one"))))
        runCurrent()
        assertEquals(first.id, viewModel.uiState.value.selectedSessionId)
        assertEquals("one.txt", viewModel.uiState.value.selection.single().displayName)
        assertTrue(viewModel.uiState.value.canConfirmSend)

        viewModel.onAction(FileAction.ConfirmSend)
        runCurrent()
        assertEquals(first.id, files.created.single().ownerSessionId)
        assertEquals("one", files.created.single().files.single().id.value)
    }

    @Test
    fun multipleSessionsAndSharedDraftRequireExplicitRecipientAndNeverAutoSend() = runTest(dispatcher) {
        val files = FakeFiles()
        val viewModel = viewModel(FakeSessions(active(first, second)), files)
        viewModel.onAction(FileAction.SharedSelectionReceived(listOf(candidate("shared"))))
        runCurrent()

        assertNull(viewModel.uiState.value.selectedSessionId)
        assertTrue(viewModel.uiState.value.recipientSelectionRequired)
        assertFalse(viewModel.uiState.value.canConfirmSend)
        assertTrue(files.created.isEmpty())

        viewModel.onAction(FileAction.RecipientSelected(second.id))
        viewModel.onAction(FileAction.ConfirmSend)
        runCurrent()
        assertEquals(second.id, files.created.single().ownerSessionId)
    }

    @Test
    fun incomingApprovalRequestsDestinationAndCancellationIsRecoverable() = runTest(dispatcher) {
        val files = FakeFiles()
        val viewModel = viewModel(FakeSessions(active(first)), files)
        val transferId = FileTransferId("incoming")

        viewModel.onAction(FileAction.ApproveIncoming(transferId))
        assertEquals(FileEffect.ChooseDestination(transferId), viewModel.effects.value)
        viewModel.onAction(FileAction.DestinationCancelled(transferId))
        runCurrent()
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("папк", ignoreCase = true))
        viewModel.onAction(FileAction.DestinationSelected(transferId, FileDestinationId("tree://downloads")))
        runCurrent()

        assertEquals(listOf(transferId to FileDestinationId("tree://downloads")), files.approved)
    }

    @Test
    fun lostRecipientIsClearedAndCancelRetryUseCasesAreInvoked() = runTest(dispatcher) {
        val sessions = FakeSessions(active(first, second))
        val files = FakeFiles()
        val viewModel = viewModel(sessions, files)
        viewModel.onAction(FileAction.RecipientSelected(second.id))
        sessions.mutable.value = active(first)
        runCurrent()

        assertNull(viewModel.uiState.value.selectedSessionId)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("отключ", ignoreCase = true))
        val id = FileTransferId("transfer")
        viewModel.onAction(FileAction.Cancel(id))
        viewModel.onAction(FileAction.Retry(id))
        runCurrent()
        assertEquals(listOf(id), files.cancelled)
        assertEquals(listOf(id), files.retried)
    }

    private fun viewModel(sessions: FakeSessions, files: FakeFiles) = FileViewModel(
        observeBrowserSessions = ObserveBrowserSessionsUseCase(sessions),
        observeTransfers = ObserveFileTransfersUseCase(files),
        createTransfers = CreateFileTransfersUseCase(files),
        approveTransfer = ApproveFileTransferUseCase(files),
        cancelTransfer = CancelFileTransferUseCase(files),
        retryTransfer = RetryFileTransferUseCase(files),
        nowEpochMillis = { 1_000 },
    )

    private class FakeSessions(initial: BrowserSessionState) : BrowserSessionRepository {
        val mutable = MutableStateFlow(initial)
        override val state: StateFlow<BrowserSessionState> = mutable
        override suspend fun approve(requestId: PairingRequestId) = Unit
        override suspend fun deny(requestId: PairingRequestId) = Unit
        override suspend fun revoke(sessionId: BrowserSessionId) = Unit
    }

    private class FakeFiles : FileTransferRepository {
        override val state = MutableStateFlow(FileTransferSnapshot(emptyList()))
        val created = mutableListOf<ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest>()
        val approved = mutableListOf<Pair<FileTransferId, FileDestinationId?>>()
        val cancelled = mutableListOf<FileTransferId>()
        val retried = mutableListOf<FileTransferId>()
        override suspend fun create(request: ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest) =
            FileTransferOperationResult.Accepted.also { created += request }
        override suspend fun approve(transferId: FileTransferId, destinationId: FileDestinationId?) =
            FileTransferOperationResult.Accepted.also { approved += transferId to destinationId }
        override suspend fun cancel(transferId: FileTransferId) =
            FileTransferOperationResult.Accepted.also { cancelled += transferId }
        override suspend fun retry(transferId: FileTransferId) =
            FileTransferOperationResult.Accepted.also { retried += transferId }
        override suspend fun verify(request: ru.hznik.devicebridge.domain.file.VerifyFileTransferRequest) =
            FileTransferOperationResult.Accepted
    }

    private fun candidate(id: String) = FileSelectionItem(
        transferId = FileTransferId(id), uri = "content://$id", displayName = "$id.txt",
        sizeBytes = 4, mimeType = "text/plain", sha256 = "a".repeat(64),
    )

    private fun active(vararg sessions: BrowserSession) = BrowserSessionState.active(
        generationId = generation, pairingCode = PairingCodeState("123456", 60_000), sessions = sessions.toList(),
    )

    private companion object {
        val generation = ServerGenerationId(1)
        val first = BrowserSession(BrowserSessionId("one"), generation, "Chrome", "192.168.1.2", 1)
        val second = BrowserSession(BrowserSessionId("two"), generation, "Edge", "192.168.1.3", 1)
    }
}
