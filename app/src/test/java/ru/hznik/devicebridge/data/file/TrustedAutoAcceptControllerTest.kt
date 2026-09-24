package ru.hznik.devicebridge.data.file

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferEvent
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferReducer
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.file.FileTransferState
import ru.hznik.devicebridge.domain.file.VerifyFileTransferRequest
import ru.hznik.devicebridge.domain.repository.FileTransferRepository
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingCodeState
import ru.hznik.devicebridge.domain.settings.DestinationTree
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId

@OptIn(ExperimentalCoroutinesApi::class)
class TrustedAutoAcceptControllerTest {

    @Test
    fun trustedSessionWithEnabledSettingIsAcceptedIntoDefaultDestination() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val fixture = Fixture(this)
        fixture.offer("t1", TRUSTED_SESSION)

        assertEquals(listOf(FileTransferId("t1")), fixture.transfers.approved.map { it.first })
        assertEquals(FileDestinationId("t1"), fixture.transfers.approved.single().second)
        assertEquals(TREE.value, fixture.leases.uri(FileTransferId("t1"), FileDestinationId("t1")))
        assertEquals(setOf(FileTransferId("t1")), fixture.controller.autoAccepted.value)
        fixture.controller.stop()
    }

    @Test
    fun resumeRetryOfOrdinarySessionIsApprovedIntoTheFolderHoldingItsPart() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val fixture = Fixture(this, settings = ENABLED.copy(autoAcceptTrustedFiles = false))
        fixture.resumeIds += "t1"
        fixture.retainedTrees += TREE.value
        fixture.offer("t1", ORDINARY_SESSION)

        assertEquals(listOf(FileTransferId("t1")), fixture.transfers.approved.map { it.first })
        // Continuing a kept part is not an automatic acceptance of a new file.
        assertTrue(fixture.controller.autoAccepted.value.isEmpty())
        fixture.controller.stop()
    }

    @Test
    fun resumeRetryWithoutAPartInTheDefaultFolderWaitsForManualApproval() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val fixture = Fixture(this, settings = ENABLED.copy(autoAcceptTrustedFiles = false))
        fixture.resumeIds += "t1"
        fixture.offer("t1", ORDINARY_SESSION)

        assertTrue(fixture.transfers.approved.isEmpty())
        fixture.controller.stop()
    }

    @Test
    fun retriedTransferIsDecidedAgain() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture(this)
        fixture.offer("t1", TRUSTED_SESSION)
        fixture.transfers.update(FileTransferId("t1")) {
            FileTransferReducer.reduce(
                FileTransferReducer.reduce(it, FileTransferEvent.Started),
                FileTransferEvent.Failed(ru.hznik.devicebridge.domain.file.FileTransferFailure.StreamFailed),
            )
        }
        fixture.transfers.update(FileTransferId("t1")) {
            FileTransferReducer.reduce(
                FileTransferState.queued(it.generationId, it.ownerSessionId, it.metadata),
                FileTransferEvent.Connecting,
            )
        }

        assertEquals(2, fixture.transfers.approved.count { it.first == FileTransferId("t1") })
        fixture.controller.stop()
    }

    @Test
    fun ordinarySessionWaitsForManualApproval() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture(this)
        fixture.offer("t1", ORDINARY_SESSION)

        assertTrue(fixture.transfers.approved.isEmpty())
        assertTrue(fixture.controller.autoAccepted.value.isEmpty())
        fixture.controller.stop()
    }

    @Test
    fun disabledSettingWaitsForManualApproval() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture(this, settings = ENABLED.copy(autoAcceptTrustedFiles = false))
        fixture.offer("t1", TRUSTED_SESSION)

        assertTrue(fixture.transfers.approved.isEmpty())
        fixture.controller.stop()
    }

    @Test
    fun missingDestinationWaitsForManualApproval() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture(this, settings = ENABLED.copy(destinationTree = null))
        fixture.offer("t1", TRUSTED_SESSION)

        assertTrue(fixture.transfers.approved.isEmpty())
        fixture.controller.stop()
    }

    @Test
    fun unavailableDestinationPausesWithoutLosingTheOffer() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture(this, destinationAvailable = false)
        fixture.offer("t1", TRUSTED_SESSION)

        assertTrue(fixture.transfers.approved.isEmpty())
        assertEquals(setOf(FileTransferId("t1")), fixture.controller.paused.value)
        assertEquals(null, fixture.leases.uri(FileTransferId("t1"), FileDestinationId("t1")))

        fixture.destinationAvailable = true
        fixture.settings.value = fixture.settings.value.copy(
            destinationTree = DestinationTree("content://documents/tree/inbox-2"),
        )
        runCurrent()

        assertEquals(listOf(FileTransferId("t1")), fixture.transfers.approved.map { it.first })
        assertTrue(fixture.controller.paused.value.isEmpty())
        fixture.controller.stop()
    }

    @Test
    fun repeatedSnapshotsApproveOnlyOnce() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture(this)
        fixture.transfers.approveResult = FileTransferOperationResult.InvalidState
        fixture.offer("t1", TRUSTED_SESSION)
        fixture.queue("unrelated", ORDINARY_SESSION)
        fixture.sessions.value = sessionState(TRUSTED_SESSION)
        runCurrent()

        assertEquals(1, fixture.transfers.approved.size)
        assertTrue(fixture.controller.autoAccepted.value.isEmpty())
        assertEquals(null, fixture.leases.uri(FileTransferId("t1"), FileDestinationId("t1")))
        fixture.controller.stop()
    }

    @Test
    fun manualDestinationRegisteredFirstIsNotReplaced() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture(this)
        val manualLease = fixture.lease("content://manual/tree")
        fixture.leases.register(FileTransferId("t1"), manualLease)

        fixture.offer("t1", TRUSTED_SESSION)

        assertTrue(fixture.transfers.approved.isEmpty())
        assertEquals(
            "content://manual/tree",
            fixture.leases.uri(FileTransferId("t1"), FileDestinationId("t1")),
        )
        fixture.controller.stop()
    }

    @Test
    fun stoppedControllerIgnoresLaterOffersAndClearsState() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture(this)
        fixture.offer("t1", TRUSTED_SESSION)
        assertEquals(setOf(FileTransferId("t1")), fixture.controller.autoAccepted.value)

        fixture.controller.stop()
        fixture.offer("t2", TRUSTED_SESSION)

        assertEquals(listOf(FileTransferId("t1")), fixture.transfers.approved.map { it.first })
        assertTrue(fixture.controller.autoAccepted.value.isEmpty())
        assertTrue(fixture.controller.paused.value.isEmpty())
    }

    @Test
    fun revokedTrustedSessionOffersAreNotAccepted() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture(this)
        fixture.queue("t1", TRUSTED_SESSION)
        fixture.sessions.value = sessionState(ORDINARY_SESSION)
        fixture.promote("t1")

        assertTrue(fixture.transfers.approved.isEmpty())
        assertFalse(FileTransferId("t1") in fixture.controller.autoAccepted.value)
        fixture.controller.stop()
    }

    @Test
    fun filesAcceptedFromANotificationAreApprovedAsTheirTurnComes() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val fixture = Fixture(this, settings = ENABLED.copy(autoAcceptTrustedFiles = false))
        fixture.queue("t1", ORDINARY_SESSION)
        fixture.queue("t2", ORDINARY_SESSION)
        fixture.queue("t3", ORDINARY_SESSION)
        fixture.promote("t1")
        assertTrue(fixture.transfers.approved.isEmpty())

        fixture.notificationAccepted.accept(listOf("t1", "t2", "t3").map(::FileTransferId))
        assertEquals(listOf(FileTransferId("t1")), fixture.transfers.approved.map { it.first })

        fixture.start("t1")
        fixture.promote("t2")

        assertEquals(
            listOf(FileTransferId("t1"), FileTransferId("t2")),
            fixture.transfers.approved.map { it.first },
        )
        // Only an automatic acceptance of a trusted browser is marked as one.
        assertTrue(fixture.controller.autoAccepted.value.isEmpty())
        // A started file is no longer waiting for the notification's decision.
        assertEquals(
            setOf(FileTransferId("t2"), FileTransferId("t3")),
            fixture.notificationAccepted.ids.value,
        )
        fixture.controller.stop()
        assertTrue(fixture.notificationAccepted.ids.value.isEmpty())
    }

    @Test
    fun notificationAcceptWithoutAFolderWaitsForThePerson() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture(
            this,
            settings = ENABLED.copy(autoAcceptTrustedFiles = false, destinationTree = null),
        )
        fixture.offer("t1", ORDINARY_SESSION)

        fixture.notificationAccepted.accept(listOf(FileTransferId("t1")))

        assertTrue(fixture.transfers.approved.isEmpty())
        assertEquals(setOf(FileTransferId("t1")), fixture.controller.paused.value)
        fixture.controller.stop()
    }

    @Test
    fun notificationAcceptOfAStartedFileIsDropped() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture(this, settings = ENABLED.copy(autoAcceptTrustedFiles = false))
        fixture.offer("t1", ORDINARY_SESSION)
        fixture.start("t1")

        fixture.notificationAccepted.accept(listOf(FileTransferId("t1"), FileTransferId("gone")))

        assertTrue(fixture.transfers.approved.isEmpty())
        assertTrue(fixture.notificationAccepted.ids.value.isEmpty())
        fixture.controller.stop()
    }

    private class Fixture(
        scope: TestScope,
        settings: DeviceSettings = ENABLED,
        var destinationAvailable: Boolean = true,
    ) {
        val resumeIds = mutableSetOf<String>()
        val retainedTrees = mutableSetOf<String>()
        val transfers = FakeTransfers()
        val sessions = MutableStateFlow(sessionState(TRUSTED_SESSION, ORDINARY_SESSION))
        val settings = MutableStateFlow(settings)
        val leases = FileDestinationLeaseRegistry()
        val notificationAccepted = NotificationAcceptedTransfers()
        private val permissions = object : DocumentTreePermissionGateway {
            override fun acquire(uri: String, grantFlags: Int) = true
            override fun isAvailable(uri: String) = true
            override fun release(uri: String, grantFlags: Int) = Unit
        }
        val controller = TrustedAutoAcceptController(
            transfers = transfers,
            sessions = sessions,
            settings = this.settings,
            destinations = PersistedDestinationOpener { uri ->
                if (destinationAvailable) {
                    DestinationApproval.Approved(lease(uri))
                } else {
                    DestinationApproval.Unavailable
                }
            },
            leases = leases,
            isResumeRetry = { id -> id.value in resumeIds },
            hasRetainedPart = { _, treeUri -> treeUri in retainedTrees },
            notificationAccepted = notificationAccepted,
        )

        init {
            controller.start(scope.backgroundScope)
        }

        fun lease(uri: String) = ScopedDocumentTreeLease(
            uri = uri,
            grantFlags = 3,
            permissions = permissions,
            releasePermissionOnClose = false,
        )

        fun queue(id: String, owner: BrowserSession) {
            transfers.put(
                FileTransferState.queued(
                    generationId = GENERATION,
                    ownerSessionId = owner.id,
                    metadata = FileTransferMetadata(
                        id = FileTransferId(id),
                        displayName = "$id.png",
                        sizeBytes = 10,
                        mimeType = "image/png",
                        sha256 = "a".repeat(64),
                        direction = FileTransferDirection.BROWSER_TO_ANDROID,
                    ),
                ),
            )
        }

        fun promote(id: String) {
            transfers.update(FileTransferId(id)) {
                FileTransferReducer.reduce(it, FileTransferEvent.Connecting)
            }
        }

        fun start(id: String) {
            transfers.update(FileTransferId(id)) {
                FileTransferReducer.reduce(it, FileTransferEvent.Started)
            }
        }

        fun offer(id: String, owner: BrowserSession) {
            queue(id, owner)
            promote(id)
        }
    }

    private class FakeTransfers : FileTransferRepository {
        private val mutableState = MutableStateFlow(FileTransferSnapshot(emptyList()))
        override val state: StateFlow<FileTransferSnapshot> = mutableState
        val approved = mutableListOf<Pair<FileTransferId, FileDestinationId?>>()
        var approveResult: FileTransferOperationResult = FileTransferOperationResult.Accepted

        fun put(item: FileTransferState) {
            mutableState.value = FileTransferSnapshot(mutableState.value.items + item)
        }

        fun update(id: FileTransferId, transform: (FileTransferState) -> FileTransferState) {
            mutableState.value = FileTransferSnapshot(
                mutableState.value.items.map { if (it.metadata.id == id) transform(it) else it },
            )
        }

        override suspend fun approve(
            transferId: FileTransferId,
            destinationId: FileDestinationId?,
        ): FileTransferOperationResult {
            approved += transferId to destinationId
            return approveResult
        }

        override suspend fun create(request: CreateFileTransfersRequest) = error("Not used")
        override suspend fun cancel(transferId: FileTransferId) = error("Not used")
        override suspend fun retry(transferId: FileTransferId) = error("Not used")
        override suspend fun verify(request: VerifyFileTransferRequest) = error("Not used")
    }

    private companion object {
        val GENERATION = ServerGenerationId(1)
        val TREE = DestinationTree("content://documents/tree/inbox")
        val ENABLED = DeviceSettings.defaults().copy(
            destinationTree = TREE,
            autoAcceptTrustedFiles = true,
        )
        val TRUSTED_SESSION = BrowserSession(
            id = BrowserSessionId("trusted"),
            generationId = GENERATION,
            browserLabel = "Chrome",
            sourceIpv4 = "192.168.1.3",
            connectedAtElapsedRealtimeMs = 1,
            trustedBrowserId = TrustedBrowserId("trusted-browser"),
        )
        val ORDINARY_SESSION = BrowserSession(
            id = BrowserSessionId("ordinary"),
            generationId = GENERATION,
            browserLabel = "Edge",
            sourceIpv4 = "192.168.1.4",
            connectedAtElapsedRealtimeMs = 2,
        )

        fun sessionState(vararg sessions: BrowserSession) = BrowserSessionState.active(
            generationId = GENERATION,
            pairingCode = PairingCodeState("123456", 60_000),
            sessions = sessions.toList(),
        )
    }
}
