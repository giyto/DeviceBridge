package ru.hznik.devicebridge.feature.file

import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileDraftId
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository
import ru.hznik.devicebridge.domain.repository.FileTransferRepository
import ru.hznik.devicebridge.domain.repository.SettingsRepository
import ru.hznik.devicebridge.domain.settings.DestinationTree
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.settings.SettingsUpdateResult
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
import ru.hznik.devicebridge.domain.usecase.ObserveSettingsUseCase
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
        assertTrue(files.created.single().files.single().id.value.startsWith("android-"))
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
        assertEquals(FileEffect.ChooseDestination(transferId), viewModel.effects.first())
        viewModel.onAction(FileAction.DestinationCancelled(transferId))
        runCurrent()
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("папк", ignoreCase = true))
        viewModel.onAction(FileAction.DestinationSelected(transferId, FileDestinationId("tree://downloads")))
        runCurrent()

        assertEquals(listOf(transferId to FileDestinationId("tree://downloads")), files.approved)
    }

    @Test
    fun incomingWithSavedDestinationOffersCurrentFolderOrPicker() = runTest(dispatcher) {
        val files = FakeFiles()
        val settings = FakeSettings(
            DeviceSettings.defaults().copy(
                destinationTree = DestinationTree("content://provider/tree/saved"),
            ),
        )
        val viewModel = viewModel(FakeSessions(active(first)), files, settings)
        val transferId = FileTransferId("incoming-saved")
        runCurrent()

        viewModel.onAction(FileAction.ApproveIncoming(transferId))
        assertEquals(
            FileEffect.UseDefaultDestination(
                transferId,
                "content://provider/tree/saved",
            ),
            viewModel.effects.first(),
        )

        viewModel.onAction(FileAction.ChangeIncomingDestination(transferId))
        assertEquals(FileEffect.ChooseDestination(transferId), viewModel.effects.first())
    }

    @Test
    fun revokedSavedDestinationCancelAndReplacementContinueSameTransferOnce() =
        runTest(dispatcher) {
            val files = FakeFiles()
            val settings = FakeSettings(
                DeviceSettings.defaults().copy(
                    destinationTree = DestinationTree("content://provider/tree/revoked"),
                ),
            )
            val viewModel = viewModel(FakeSessions(active(first)), files, settings)
            val transferId = FileTransferId("incoming-revoked")
            runCurrent()

            viewModel.onAction(FileAction.ApproveIncoming(transferId))
            assertEquals(
                FileEffect.UseDefaultDestination(
                    transferId,
                    "content://provider/tree/revoked",
                ),
                viewModel.effects.first(),
            )

            viewModel.onAction(FileAction.DestinationUnavailable(transferId))
            viewModel.onAction(FileAction.ChangeIncomingDestination(transferId))
            assertEquals(FileEffect.ChooseDestination(transferId), viewModel.effects.first())

            viewModel.onAction(FileAction.DestinationCancelled(transferId))
            assertTrue(files.approved.isEmpty())
            viewModel.onAction(FileAction.ChangeIncomingDestination(transferId))
            viewModel.onAction(
                FileAction.DestinationSelected(
                    transferId,
                    FileDestinationId("tree://replacement"),
                ),
            )
            runCurrent()

            assertEquals(
                listOf(transferId to FileDestinationId("tree://replacement")),
                files.approved,
            )
        }

    @Test
    fun selectionLimitTracksLatestRepositorySetting() = runTest(dispatcher) {
        val settings = FakeSettings(
            DeviceSettings.defaults().copy(effectiveFileLimitBytes = 512),
        )
        val viewModel = viewModel(FakeSessions(active(first)), FakeFiles(), settings)
        runCurrent()
        assertEquals(512, viewModel.uiState.value.effectiveFileLimitBytes)

        settings.current.value = settings.current.value.copy(effectiveFileLimitBytes = 256)
        runCurrent()
        assertEquals(256, viewModel.uiState.value.effectiveFileLimitBytes)
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

    @Test
    fun pickerResultsAppendDeduplicateAndCancelWithoutDroppingExistingDraft() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeSessions(active(first)), FakeFiles())
            val firstLease = FakeDraftSourceLease()
            val duplicateLease = FakeDraftSourceLease()
            val differentLease = FakeDraftSourceLease()
            viewModel.onAction(
                FileAction.SelectionReceived(
                    listOf(candidate("first", "content://same", "report.txt", firstLease)),
                ),
            )
            viewModel.onAction(FileAction.SelectionReceived(emptyList()))
            viewModel.onAction(
                FileAction.SelectionReceived(
                    listOf(
                        candidate("duplicate", "content://same", "report.txt", duplicateLease),
                        candidate("different", "content://other", "report.txt", differentLease),
                    ),
                ),
            )
            runCurrent()

            assertEquals(listOf("first", "different"), viewModel.uiState.value.selection.map { it.id.value })
            assertTrue(duplicateLease.released)
            assertFalse(firstLease.released)
            assertFalse(differentLease.released)
        }

    @Test
    fun removeAndClearReleaseDraftSourcesWithoutCreatingTransfers() = runTest(dispatcher) {
        val files = FakeFiles()
        val firstLease = FakeDraftSourceLease()
        val secondLease = FakeDraftSourceLease()
        val viewModel = viewModel(FakeSessions(active(first)), files)
        viewModel.onAction(
            FileAction.SelectionReceived(
                listOf(
                    candidate("first", lease = firstLease),
                    candidate("second", lease = secondLease),
                ),
            ),
        )

        viewModel.onAction(FileAction.RemoveDraftItem(FileDraftId("first")))
        runCurrent()
        assertTrue(firstLease.released)
        assertEquals(listOf("second"), viewModel.uiState.value.selection.map { it.id.value })
        viewModel.onAction(FileAction.ClearDraft)
        runCurrent()

        assertTrue(secondLease.released)
        assertTrue(viewModel.uiState.value.selection.isEmpty())
        assertTrue(files.created.isEmpty())
    }

    @Test
    fun rejectedConfirmationRollsBackLeaseAndKeepsDraftForRetry() = runTest(dispatcher) {
        val files = FakeFiles().apply { createResult = FileTransferOperationResult.InvalidState }
        val lease = FakeDraftSourceLease()
        val viewModel = viewModel(FakeSessions(active(first)), files)
        viewModel.onAction(FileAction.SelectionReceived(listOf(candidate("retry", lease = lease))))

        viewModel.onAction(FileAction.ConfirmSend)
        runCurrent()

        assertEquals(1, lease.promoted.size)
        assertEquals(lease.promoted, lease.rolledBack)
        assertTrue(viewModel.uiState.value.selection.isNotEmpty())
        files.createResult = FileTransferOperationResult.Accepted
        viewModel.onAction(FileAction.ConfirmSend)
        runCurrent()
        assertEquals(2, lease.promoted.size)
        assertEquals(1, lease.committed.size)
        assertTrue(viewModel.uiState.value.selection.isEmpty())
    }

    @Test
    fun filePickerEffectIsDeliveredOnceWithoutReplay() = runTest(dispatcher) {
        val viewModel = viewModel(FakeSessions(active(first)), FakeFiles())
        runCurrent()
        val firstEffect = async { viewModel.effects.first() }
        runCurrent()

        viewModel.onAction(FileAction.PickFiles)

        assertEquals(FileEffect.ChooseFiles, firstEffect.await())
        assertNull(
            withTimeoutOrNull(100) {
                viewModel.effects.first()
            },
        )
    }

    private fun viewModel(
        sessions: FakeSessions,
        files: FakeFiles,
        settings: FakeSettings = FakeSettings(),
    ) = FileViewModel(
        observeBrowserSessions = ObserveBrowserSessionsUseCase(sessions),
        observeTransfers = ObserveFileTransfersUseCase(files),
        observeSettings = ObserveSettingsUseCase(settings),
        createTransfers = CreateFileTransfersUseCase(files),
        approveTransfer = ApproveFileTransferUseCase(files),
        cancelTransfer = CancelFileTransferUseCase(files),
        retryTransfer = RetryFileTransferUseCase(files),
        nowEpochMillis = { 1_000 },
    )

    private class FakeSettings(
        initial: DeviceSettings = DeviceSettings.defaults(),
    ) : SettingsRepository {
        val current = MutableStateFlow(initial)
        override val settings: Flow<DeviceSettings> = current

        override suspend fun updateDeviceName(value: String): SettingsUpdateResult =
            SettingsUpdateResult.Updated(current.value)

        override suspend fun updateRetentionDays(value: Int): SettingsUpdateResult =
            SettingsUpdateResult.Updated(current.value)

        override suspend fun updateDestinationTree(value: DestinationTree?): SettingsUpdateResult {
            current.value = current.value.copy(destinationTree = value)
            return SettingsUpdateResult.Updated(current.value)
        }

        override suspend fun updateEffectiveFileLimitBytes(value: Long): SettingsUpdateResult =
            SettingsUpdateResult.Updated(current.value)

        override suspend fun updateAutoAcceptTrustedFiles(enabled: Boolean): SettingsUpdateResult =
            SettingsUpdateResult.Updated(current.value)

        override suspend fun updateIdleStopTimeout(
            value: ru.hznik.devicebridge.domain.settings.IdleStopTimeout,
        ): SettingsUpdateResult = SettingsUpdateResult.Updated(current.value)
    }

    private class FakeSessions(initial: BrowserSessionState) : BrowserSessionRepository {
        val mutable = MutableStateFlow(initial)
        override val state: StateFlow<BrowserSessionState> = mutable
        override val connectedSessionIds: StateFlow<Set<BrowserSessionId>> =
            MutableStateFlow(emptySet())
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
        var createResult: FileTransferOperationResult = FileTransferOperationResult.Accepted
        override suspend fun create(request: ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest) =
            createResult.also { created += request }
        override suspend fun approve(transferId: FileTransferId, destinationId: FileDestinationId?) =
            FileTransferOperationResult.Accepted.also { approved += transferId to destinationId }
        override suspend fun cancel(transferId: FileTransferId) =
            FileTransferOperationResult.Accepted.also { cancelled += transferId }
        override suspend fun retry(transferId: FileTransferId) =
            FileTransferOperationResult.Accepted.also { retried += transferId }
        override suspend fun verify(request: ru.hznik.devicebridge.domain.file.VerifyFileTransferRequest) =
            FileTransferOperationResult.Accepted
    }

    private fun candidate(
        id: String,
        sourceIdentity: String = "content://$id",
        displayName: String = "$id.txt",
        lease: FakeDraftSourceLease = FakeDraftSourceLease(),
    ) = FileDraftItem(
        id = FileDraftId(id),
        sourceIdentity = sourceIdentity,
        sourceLease = lease,
        displayName = displayName,
        sizeBytes = 4,
        mimeType = "text/plain",
        sha256 = "a".repeat(64),
    )

    private class FakeDraftSourceLease : DraftSourceLease {
        val promoted = mutableListOf<FileTransferId>()
        val rolledBack = mutableListOf<FileTransferId>()
        val committed = mutableListOf<FileTransferId>()
        var released = false
        override fun promote(transferId: FileTransferId): Boolean = true.also { promoted += transferId }
        override fun rollback(transferId: FileTransferId) { rolledBack += transferId }
        override fun commit(transferId: FileTransferId) { committed += transferId }
        override fun release() { released = true }
    }

    private fun active(vararg sessions: BrowserSession) = BrowserSessionState.active(
        generationId = generation, pairingCode = PairingCodeState("123456", 60_000), sessions = sessions.toList(),
    )

    private companion object {
        val generation = ServerGenerationId(1)
        val first = BrowserSession(BrowserSessionId("one"), generation, "Chrome", "192.168.1.2", 1)
        val second = BrowserSession(BrowserSessionId("two"), generation, "Edge", "192.168.1.3", 1)
    }
}
