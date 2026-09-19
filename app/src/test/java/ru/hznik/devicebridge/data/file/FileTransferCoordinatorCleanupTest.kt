package ru.hznik.devicebridge.data.file

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileCommandId
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferEvent
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingCodeState
import ru.hznik.devicebridge.domain.session.ServerGenerationId

class FileTransferCoordinatorCleanupTest {

    @Test
    fun queuedCancelDoesNotOpenResources() = runTest {
        val coordinator = coordinator()
        coordinator.activate(generation)
        coordinator.create(request("batch", "first", "queued"))

        coordinator.cancel(FileTransferId("queued"))

        assertEquals(
            FileTransferPhase.CANCELLED,
            coordinator.state.value.item(FileTransferId("queued"))?.phase,
        )
        assertEquals(FileTransferPhase.CONNECTING, phase(coordinator, "first"))
    }

    @Test
    fun activeCancelClosesResourcesBeforePromotingNextItem() = runTest {
        val coordinator = coordinator()
        coordinator.activate(generation)
        coordinator.create(request("batch", "first", "next"))
        coordinator.approve(FileTransferId("first"), FileDestinationId("folder"))
        val resources = RecordingResources()
        assertTrue(coordinator.attachResources(FileTransferId("first"), resources))

        coordinator.cancel(FileTransferId("first"))

        assertEquals(listOf("cancel-job", "close-streams", "cleanup-partial"), resources.events)
        assertEquals(FileTransferPhase.CANCELLED, phase(coordinator, "first"))
        assertEquals(FileTransferPhase.CONNECTING, phase(coordinator, "next"))
    }
    @Test
    fun disconnectAndRevokeCancelOnlyOwnedItemsAndInvalidateGrants() = runTest {
        val grants = grantRegistry()
        val coordinator = coordinator(grants)
        coordinator.activate(generation)
        coordinator.create(request("owned", "owned"))
        coordinator.create(request("foreign", "foreign", owner = otherSession))
        val disconnectGrant = grants.issue(generation, session.id, FileTransferId("owned"))

        coordinator.onSessionDisconnected(generation, session.id)

        assertEquals(FileTransferPhase.FAILED, phase(coordinator, "owned"))
        assertEquals(
            FileTransferFailure.SessionUnavailable,
            coordinator.state.value.item(FileTransferId("owned"))?.failure,
        )
        assertEquals(FileTransferPhase.CONNECTING, phase(coordinator, "foreign"))
        assertNull(
            grants.consume(
                disconnectGrant.token,
                generation,
                session.id,
                FileTransferId("owned"),
            ),
        )

        val revokeGrant = grants.issue(
            generation,
            otherSession.id,
            FileTransferId("foreign"),
        )
        coordinator.onSessionRevoked(generation, otherSession.id)
        assertEquals(FileTransferPhase.FAILED, phase(coordinator, "foreign"))
        assertEquals(
            FileTransferFailure.SessionUnavailable,
            coordinator.state.value.item(FileTransferId("foreign"))?.failure,
        )
        assertNull(
            grants.consume(
                revokeGrant.token,
                generation,
                otherSession.id,
                FileTransferId("foreign"),
            ),
        )
    }

    @Test
    fun networkFailureCleansActiveResourcesAndPromotesQueue() = runTest {
        val coordinator = coordinator()
        coordinator.activate(generation)
        coordinator.create(request("batch", "first", "next"))
        coordinator.approve(FileTransferId("first"), FileDestinationId("folder"))
        val resources = RecordingResources()
        coordinator.attachResources(FileTransferId("first"), resources)

        coordinator.onNetworkFailure(FileTransferId("first"))

        assertEquals(listOf("cancel-job", "close-streams", "cleanup-partial"), resources.events)
        assertEquals(FileTransferPhase.FAILED, phase(coordinator, "first"))
        assertEquals(
            FileTransferFailure.StreamFailed,
            coordinator.state.value.item(FileTransferId("first"))?.failure,
        )
        assertEquals(FileTransferPhase.CONNECTING, phase(coordinator, "next"))
    }
    @Test
    fun genericFailureUsesTerminalCleanupBeforePromotingNextItem() = runTest {
        val coordinator = coordinator()
        coordinator.activate(generation)
        coordinator.create(request("batch-terminal-failure", "first", "next"))
        coordinator.approve(FileTransferId("first"), FileDestinationId("folder"))
        val resources = RecordingResources()
        coordinator.attachResources(FileTransferId("first"), resources)

        coordinator.transition(
            FileTransferId("first"),
            FileTransferEvent.Failed(FileTransferFailure.ChecksumMismatch),
        )

        assertEquals(
            listOf("cancel-job", "close-streams", "cleanup-partial"),
            resources.events,
        )
        assertEquals(FileTransferPhase.FAILED, phase(coordinator, "first"))
        assertEquals(FileTransferPhase.CONNECTING, phase(coordinator, "next"))
    }

    @Test
    fun successfulTerminalizationClosesStreamsWithoutDeletingCommittedOutput() = runTest {
        val coordinator = coordinator()
        coordinator.activate(generation)
        coordinator.create(request("batch-terminal-success", "first", "next"))
        coordinator.approve(FileTransferId("first"), FileDestinationId("folder"))
        val resources = RecordingResources()
        coordinator.attachResources(FileTransferId("first"), resources)
        coordinator.transition(
            FileTransferId("first"),
            FileTransferEvent.Progressed(1, 1),
        )
        coordinator.transition(FileTransferId("first"), FileTransferEvent.Verifying)

        coordinator.transition(FileTransferId("first"), FileTransferEvent.Completed)

        assertEquals(listOf("close-streams"), resources.events)
        assertEquals(FileTransferPhase.COMPLETED, phase(coordinator, "first"))
        assertEquals(FileTransferPhase.CONNECTING, phase(coordinator, "next"))
    }
    @Test
    fun retryRejectsChangedAndroidSourceWithoutCreatingDuplicateQueueItem() = runTest {
        val coordinator = coordinator(
            retrySourceValidator = FileRetrySourceValidator {
                FileRetrySourceValidation.CHANGED
            },
        )
        coordinator.activate(generation)
        coordinator.create(
            request(
                "retry-source",
                "source-file",
                direction = FileTransferDirection.ANDROID_TO_BROWSER,
            ),
        )
        coordinator.transition(
            FileTransferId("source-file"),
            FileTransferEvent.Failed(FileTransferFailure.StreamFailed),
        )

        val result = coordinator.retry(FileTransferId("source-file"))

        assertEquals(
            ru.hznik.devicebridge.domain.file.FileTransferOperationResult.Rejected(
                FileTransferFailure.SourceUnavailable,
            ),
            result,
        )
        assertEquals(1, coordinator.state.value.items.size)
        assertEquals(FileTransferPhase.FAILED, phase(coordinator, "source-file"))
    }


    @Test
    fun serverStopCleansEveryResourceInvalidatesGrantsAndClearsGeneration() = runTest {
        val grants = grantRegistry()
        val coordinator = coordinator(grants)
        coordinator.activate(generation)
        coordinator.create(request("upload", "upload"))
        coordinator.create(
            request(
                "download",
                "download",
                direction = FileTransferDirection.ANDROID_TO_BROWSER,
            ),
        )
        coordinator.approve(FileTransferId("upload"), FileDestinationId("folder"))
        coordinator.approve(FileTransferId("download"), null)
        val uploadResources = RecordingResources()
        val downloadResources = RecordingResources()
        coordinator.attachResources(FileTransferId("upload"), uploadResources)
        coordinator.attachResources(FileTransferId("download"), downloadResources)
        val grant = grants.issue(generation, session.id, FileTransferId("download"))

        coordinator.close(generation)

        assertEquals(3, uploadResources.events.size)
        assertEquals(3, downloadResources.events.size)
        assertTrue(coordinator.state.value.items.isEmpty())
        assertNull(
            grants.consume(
                grant.token,
                generation,
                session.id,
                FileTransferId("download"),
            ),
        )
    }

    @Test
    fun wifiLockIsHeldOnlyForTransferringItemsAcrossBothDirections() = runTest {
        val wifiLock = RecordingFileTransferWifiLock()
        val coordinator = coordinator(wifiLock = wifiLock)
        coordinator.activate(generation)
        wifiLock.releaseAllCalls = 0
        coordinator.create(request("upload-batch", "upload"))
        coordinator.create(
            request(
                "download-batch",
                "download",
                direction = FileTransferDirection.ANDROID_TO_BROWSER,
            ),
        )

        assertTrue(wifiLock.acquired.isEmpty())
        coordinator.approve(FileTransferId("upload"), FileDestinationId("folder"))
        coordinator.approve(FileTransferId("download"), null)
        assertEquals(setOf("upload", "download"), wifiLock.acquired)

        coordinator.transition(FileTransferId("upload"), FileTransferEvent.Progressed(1, 1))
        coordinator.transition(FileTransferId("upload"), FileTransferEvent.Verifying)
        assertEquals(setOf("download"), wifiLock.acquired)

        coordinator.cancel(FileTransferId("download"))
        assertTrue(wifiLock.acquired.isEmpty())
        coordinator.close(generation)
        assertEquals(1, wifiLock.releaseAllCalls)
    }

    @Test
    fun repeatedTransferCancelStopCyclesReleaseResourcesAndWifiLock() = runTest {
        val wifiLock = RecordingFileTransferWifiLock()
        val coordinator = coordinator(wifiLock = wifiLock)
        val resources = mutableListOf<RecordingResources>()

        repeat(20) { cycle ->
            coordinator.activate(generation)
            val id = FileTransferId("cycle-$cycle")
            coordinator.create(request("cycle-command-$cycle", id.value))
            coordinator.approve(id, FileDestinationId("folder-$cycle"))
            resources += RecordingResources().also { attached ->
                assertTrue(coordinator.attachResources(id, attached))
            }
            coordinator.cancel(id)
            coordinator.close(generation)

            assertTrue(coordinator.state.value.items.isEmpty())
            assertTrue(wifiLock.acquired.isEmpty())
        }

        assertTrue(resources.all { it.events == listOf("cancel-job", "close-streams", "cleanup-partial") })
        assertTrue(wifiLock.acquired.isEmpty())
    }

    private fun coordinator(
        grants: DownloadGrantRegistry = grantRegistry(),
        wifiLock: FileTransferWifiLock = NoOpFileTransferWifiLock,
        retrySourceValidator: FileRetrySourceValidator = FileRetrySourceValidator.alwaysValid(),
    ) = FileTransferCoordinator(
        browserSessionState = { sessions },
        downloadGrantRegistry = grants,
        wifiLock = wifiLock,
        retrySourceValidator = retrySourceValidator,
    )

    private fun grantRegistry() = DownloadGrantRegistry(
        nowEpochMillis = { 1_000L },
        tokenSource = DownloadGrantTokenSource { bytes -> bytes.fill(7) },
    )

    private fun request(
        commandId: String,
        vararg ids: String,
        owner: BrowserSession = session,
        direction: FileTransferDirection = FileTransferDirection.BROWSER_TO_ANDROID,
    ) = CreateFileTransfersRequest(
        commandId = FileCommandId(commandId),
        generationId = generation,
        ownerSessionId = owner.id,
        files = ids.map { id -> metadata(id, direction) },
    )

    private fun metadata(
        id: String,
        direction: FileTransferDirection,
    ) = FileTransferMetadata(
        id = FileTransferId(id),
        displayName = id + ".bin",
        sizeBytes = 1,
        mimeType = "application/octet-stream",
        sha256 = "a".repeat(64),
        direction = direction,
    )

    private fun phase(
        coordinator: FileTransferCoordinator,
        id: String,
    ) = coordinator.state.value.item(FileTransferId(id))?.phase

    private class RecordingResources : FileTransferResources {
        val events = mutableListOf<String>()

        override suspend fun cancelJob() {
            events += "cancel-job"
        }

        override suspend fun closeStreams() {
            events += "close-streams"
        }

        override suspend fun cleanupPartial() {
            events += "cleanup-partial"
        }
    }

    private class RecordingFileTransferWifiLock : FileTransferWifiLock {
        val acquired = linkedSetOf<String>()
        var releaseAllCalls = 0

        override fun acquire(transferId: FileTransferId) {
            acquired += transferId.value
        }

        override fun release(transferId: FileTransferId) {
            acquired -= transferId.value
        }

        override fun releaseAll() {
            releaseAllCalls += 1
            acquired.clear()
        }
    }

    private companion object {
        val generation = ServerGenerationId(1)
        val session = session("session-1")
        val otherSession = session("session-2")
        val sessions = BrowserSessionState.active(
            generationId = generation,
            pairingCode = PairingCodeState("123456", 60_000),
            sessions = listOf(session, otherSession),
        )

        fun session(id: String) = BrowserSession(
            id = BrowserSessionId(id),
            generationId = generation,
            browserLabel = "Chrome",
            sourceIpv4 = "192.168.1.2",
            connectedAtElapsedRealtimeMs = 1,
        )
    }
}
