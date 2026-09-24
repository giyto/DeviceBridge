package ru.hznik.devicebridge.data.file

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun sessionLossAndServerStopKeepThePartWhileCancelDeletesIt() = runTest {
        val coordinator = coordinator()
        coordinator.activate(generation)
        coordinator.create(request("batch", "lost", "stopped", "cancelled"))
        coordinator.approve(FileTransferId("lost"), FileDestinationId("folder"))
        val lost = RecordingResources()
        coordinator.attachResources(FileTransferId("lost"), lost)
        coordinator.onSessionDisconnected(generation, session.id)
        assertEquals(listOf("cancel-job", "close-streams", "retain-partial"), lost.events)

        val stopCoordinator = coordinator()
        stopCoordinator.activate(generation)
        stopCoordinator.create(request("batch", "stopped"))
        stopCoordinator.approve(FileTransferId("stopped"), FileDestinationId("folder"))
        val stopped = RecordingResources()
        stopCoordinator.attachResources(FileTransferId("stopped"), stopped)
        stopCoordinator.close(generation)
        assertEquals(listOf("cancel-job", "close-streams", "retain-partial"), stopped.events)

        val cancelCoordinator = coordinator()
        cancelCoordinator.activate(generation)
        cancelCoordinator.create(request("batch", "cancelled"))
        cancelCoordinator.approve(FileTransferId("cancelled"), FileDestinationId("folder"))
        val cancelled = RecordingResources()
        cancelCoordinator.attachResources(FileTransferId("cancelled"), cancelled)
        cancelCoordinator.cancel(FileTransferId("cancelled"))
        assertEquals(listOf("cancel-job", "close-streams", "cleanup-partial"), cancelled.events)
    }

    @Test
    fun interruptedLargeUploadIsRetriedAsAResumeUntilApproved() = runTest {
        val coordinator = coordinator()
        coordinator.activate(generation)
        val big = FileTransferId("big")
        coordinator.create(request("batch", "big", sizeBytes = RESUMABLE_UPLOAD_MIN_BYTES))
        coordinator.approve(big, FileDestinationId("folder"))
        coordinator.transition(big, FileTransferEvent.Progressed(1_000, speedBytesPerSecond = 0))
        coordinator.onNetworkFailure(big)
        assertEquals(1_000L, coordinator.state.value.item(big)?.bytesTransferred)

        coordinator.retry(big)
        assertTrue(coordinator.isResumeRetry(big))

        coordinator.approve(big, FileDestinationId("folder"))
        assertFalse(coordinator.isResumeRetry(big))
    }

    @Test
    fun smallOrUnstartedUploadRetriesFromScratch() = runTest {
        val coordinator = coordinator()
        coordinator.activate(generation)
        coordinator.create(request("small", "small"))
        coordinator.create(request("fresh", "fresh", sizeBytes = RESUMABLE_UPLOAD_MIN_BYTES))
        val small = FileTransferId("small")
        coordinator.approve(small, FileDestinationId("folder"))
        coordinator.transition(small, FileTransferEvent.Progressed(1, speedBytesPerSecond = 0))
        coordinator.onNetworkFailure(small)
        coordinator.retry(small)
        assertFalse(coordinator.isResumeRetry(small))

        val fresh = FileTransferId("fresh")
        coordinator.cancel(fresh)
        coordinator.retry(fresh)
        assertFalse(coordinator.isResumeRetry(fresh))
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

        // A network drop keeps the written part so the same file can continue later.
        assertEquals(listOf("cancel-job", "close-streams", "retain-partial"), resources.events)
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
    fun runningOutOfSpaceKeepsTheLargePartAndReportsItAsResumable() = runTest {
        val coordinator = coordinator()
        coordinator.activate(generation)
        coordinator.create(request("batch", "large", sizeBytes = RESUMABLE_UPLOAD_MIN_BYTES))
        coordinator.approve(FileTransferId("large"), FileDestinationId("folder"))
        val resources = RecordingResources()
        coordinator.attachResources(FileTransferId("large"), resources)
        coordinator.transition(FileTransferId("large"), FileTransferEvent.Progressed(4096, speedBytesPerSecond = 0))

        coordinator.transition(
            FileTransferId("large"),
            FileTransferEvent.Failed(FileTransferFailure.InsufficientSpace),
        )

        assertEquals(listOf("cancel-job", "close-streams", "retain-partial"), resources.events)
        assertEquals(4096L, coordinator.state.value.item(FileTransferId("large"))?.resumableBytes())
        coordinator.retry(FileTransferId("large"))
        assertTrue(coordinator.isResumeRetry(FileTransferId("large")))
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
    fun interruptedDownloadResumesFromTheOffsetWithTheUsedGrantWithinTheWindow() = runTest {
        var now = 0L
        val coordinator = coordinator(nowEpochMillis = { now })
        val grant = interruptedDownload(coordinator, delivered = 40)
        val id = FileTransferId("download")

        assertEquals(DownloadResume.Rejected, coordinator.resumeDownload("unknown", generation, id, 40))
        assertEquals(
            DownloadResume.Stale,
            coordinator.resumeDownload(grant, generation, id, 40, expectedSha256 = "b".repeat(64)),
        )
        now = DOWNLOAD_RESUME_WINDOW_MILLIS
        val resumed = coordinator.resumeDownload(grant, generation, id, 40, expectedSha256 = "A".repeat(64))

        assertEquals(DownloadResume.Allowed(DownloadGrantScope(generation, session.id, id)), resumed)
        val item = coordinator.state.value.item(id)
        assertEquals(FileTransferPhase.TRANSFERRING, item?.phase)
        assertEquals(40L, item?.bytesTransferred)
        assertNull(item?.failure)
        coordinator.transition(id, FileTransferEvent.Progressed(100, speedBytesPerSecond = 0))
        coordinator.transition(id, FileTransferEvent.Delivered)
        assertEquals(FileTransferPhase.COMPLETED, phase(coordinator, "download"))
        assertEquals(DownloadResume.Rejected, coordinator.resumeDownload(grant, generation, id, 0))
    }

    @Test
    fun downloadResumeIsRefusedAfterTheWindowCancelChangedSourceOrWhileBusy() = runTest {
        var now = 0L
        val late = coordinator(nowEpochMillis = { now })
        val lateGrant = interruptedDownload(late, delivered = 10)
        now = DOWNLOAD_RESUME_WINDOW_MILLIS + 1
        assertEquals(
            DownloadResume.Rejected,
            late.resumeDownload(lateGrant, generation, FileTransferId("download"), 10),
        )
        now = 0
        // An expired window also forgets the grant.
        assertEquals(
            DownloadResume.Rejected,
            late.resumeDownload(lateGrant, generation, FileTransferId("download"), 10),
        )

        val changed = coordinator(
            retrySourceValidator = FileRetrySourceValidator { FileRetrySourceValidation.CHANGED },
        )
        val changedGrant = interruptedDownload(changed, delivered = 10)
        assertEquals(
            DownloadResume.Stale,
            changed.resumeDownload(changedGrant, generation, FileTransferId("download"), 10),
        )
        assertEquals(FileTransferPhase.FAILED, phase(changed, "download"))

        val busy = coordinator()
        val busyGrant = interruptedDownload(busy, delivered = 10, "next")
        // The failed download released the queue, so the next one is active now.
        assertEquals(FileTransferPhase.CONNECTING, phase(busy, "next"))
        assertEquals(
            DownloadResume.Busy,
            busy.resumeDownload(busyGrant, generation, FileTransferId("download"), 10),
        )
        busy.cancel(FileTransferId("next"))
        assertTrue(
            busy.resumeDownload(busyGrant, generation, FileTransferId("download"), 10) is DownloadResume.Allowed,
        )

        val cancelled = coordinator()
        val cancelledGrant = interruptedDownload(cancelled, delivered = 10)
        cancelled.retry(FileTransferId("download"))
        cancelled.cancel(FileTransferId("download"))
        assertEquals(
            DownloadResume.Rejected,
            cancelled.resumeDownload(cancelledGrant, generation, FileTransferId("download"), 10),
        )
    }

    @Test
    fun downloadCutWithItsSessionStaysResumableUnlessTheBrowserIsRevoked() = runTest {
        suspend fun startedDownload(coordinator: FileTransferCoordinator): String {
            coordinator.activate(generation)
            coordinator.create(
                request(
                    "download",
                    "download",
                    direction = FileTransferDirection.ANDROID_TO_BROWSER,
                    sizeBytes = 100,
                ),
            )
            val id = FileTransferId("download")
            val grant = coordinator.issueDownloadGrant(generation, session.id, id)!!
            coordinator.consumeDownloadGrant(grant.token, generation, id)!!
            coordinator.transition(id, FileTransferEvent.Started)
            coordinator.transition(id, FileTransferEvent.Progressed(30, speedBytesPerSecond = 0))
            return grant.token
        }
        val id = FileTransferId("download")

        val lost = coordinator()
        val lostGrant = startedDownload(lost)
        lost.onSessionDisconnected(generation, session.id)
        assertEquals(FileTransferFailure.StreamFailed, lost.state.value.item(id)?.failure)
        assertEquals(30L, lost.state.value.item(id)?.resumableBytes())
        assertTrue(lost.resumeDownload(lostGrant, generation, id, 30) is DownloadResume.Allowed)

        val revoked = coordinator()
        val revokedGrant = startedDownload(revoked)
        revoked.onSessionRevoked(generation, session.id)
        assertEquals(FileTransferFailure.SessionUnavailable, revoked.state.value.item(id)?.failure)
        assertNull(revoked.state.value.item(id)?.resumableBytes())
        assertEquals(DownloadResume.Rejected, revoked.resumeDownload(revokedGrant, generation, id, 30))
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
        nowEpochMillis: () -> Long = { 0L },
    ) = FileTransferCoordinator(
        browserSessionState = { sessions },
        downloadGrantRegistry = grants,
        wifiLock = wifiLock,
        retrySourceValidator = retrySourceValidator,
        nowEpochMillis = nowEpochMillis,
    )

    /** Starts the download "download" (100 bytes) and cuts it off; returns its used grant. */
    private suspend fun interruptedDownload(
        coordinator: FileTransferCoordinator,
        delivered: Long,
        vararg queued: String,
    ): String {
        coordinator.activate(generation)
        coordinator.create(
            request(
                "download",
                "download",
                *queued,
                direction = FileTransferDirection.ANDROID_TO_BROWSER,
                sizeBytes = 100,
            ),
        )
        val id = FileTransferId("download")
        val grant = coordinator.issueDownloadGrant(generation, session.id, id)!!
        coordinator.consumeDownloadGrant(grant.token, generation, id)!!
        coordinator.transition(id, FileTransferEvent.Started)
        coordinator.transition(id, FileTransferEvent.Progressed(delivered, speedBytesPerSecond = 0))
        coordinator.onNetworkFailure(id)
        assertEquals(FileTransferPhase.FAILED, phase(coordinator, "download"))
        return grant.token
    }

    private fun grantRegistry() = DownloadGrantRegistry(
        nowEpochMillis = { 1_000L },
        tokenSource = DownloadGrantTokenSource { bytes -> bytes.fill(7) },
    )

    private fun request(
        commandId: String,
        vararg ids: String,
        owner: BrowserSession = session,
        direction: FileTransferDirection = FileTransferDirection.BROWSER_TO_ANDROID,
        sizeBytes: Long = 1,
    ) = CreateFileTransfersRequest(
        commandId = FileCommandId(commandId),
        generationId = generation,
        ownerSessionId = owner.id,
        files = ids.map { id -> metadata(id, direction, sizeBytes) },
    )

    private fun metadata(
        id: String,
        direction: FileTransferDirection,
        sizeBytes: Long = 1,
    ) = FileTransferMetadata(
        id = FileTransferId(id),
        displayName = id + ".bin",
        sizeBytes = sizeBytes,
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

        override suspend fun cleanupPartial(retain: Boolean) {
            events += if (retain) "retain-partial" else "cleanup-partial"
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
