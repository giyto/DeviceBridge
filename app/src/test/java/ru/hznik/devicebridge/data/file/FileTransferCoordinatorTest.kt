package ru.hznik.devicebridge.data.file

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileCommandId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.file.FileTransferEvent
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingCodeState
import ru.hznik.devicebridge.domain.session.ServerGenerationId

class FileTransferCoordinatorTest {
    private val generation = ServerGenerationId(1)
    private val session = BrowserSession(
        id = BrowserSessionId("session-1"),
        generationId = generation,
        browserLabel = "Chrome",
        sourceIpv4 = "192.168.1.2",
        connectedAtElapsedRealtimeMs = 1,
    )
    private var sessions = activeSessions(session)

    @Test
    fun createsOwnedItemsAndKeepsIdempotentCommandOutcome() = runTest {
        val coordinator = coordinator()
        coordinator.activate(generation)
        val request = request("offer-1", "one", "two")

        assertEquals(FileTransferOperationResult.Accepted, coordinator.create(request))
        assertEquals(FileTransferOperationResult.Accepted, coordinator.create(request))

        assertEquals(listOf("one", "two"), coordinator.state.value.items.map { it.metadata.id.value })
        assertEquals(
            setOf(session.id),
            coordinator.state.value.items.map { it.ownerSessionId }.toSet(),
        )
        assertEquals(
            setOf(generation),
            coordinator.state.value.items.map { it.generationId }.toSet(),
        )
    }

    @Test
    fun conflictingReplayDoesNotMutateOriginalItems() = runTest {
        val coordinator = coordinator()
        coordinator.activate(generation)
        assertEquals(
            FileTransferOperationResult.Accepted,
            coordinator.create(request("same-message", "original")),
        )

        assertEquals(
            FileTransferOperationResult.Conflict,
            coordinator.create(request("same-message", "different")),
        )
        assertEquals(
            listOf("original"),
            coordinator.state.value.items.map { it.metadata.id.value },
        )
    }

    @Test
    fun rejectsForeignSessionClosedGenerationAndMetadataCapacityOverflow() = runTest {
        val coordinator = coordinator(maxItems = 2)
        assertEquals(
            FileTransferOperationResult.InvalidState,
            coordinator.create(request("before-activation", "one")),
        )

        coordinator.activate(generation)
        sessions = activeSessions()
        assertEquals(
            FileTransferOperationResult.Rejected(FileTransferFailure.SessionUnavailable),
            coordinator.create(request("foreign", "one")),
        )

        sessions = activeSessions(session)
        assertEquals(
            FileTransferOperationResult.Accepted,
            coordinator.create(request("full", "one", "two")),
        )
        assertEquals(
            FileTransferOperationResult.Rejected(FileTransferFailure.CapacityReached),
            coordinator.create(request("overflow", "three")),
        )
        assertEquals(2, coordinator.state.value.items.size)
    }

    @Test
    fun snapshotContainsOnlyOwnedItemsAndNeverRestartsPayload() = runTest {
        val otherSession = session("session-2")
        sessions = activeSessions(session, otherSession)
        val coordinator = coordinator()
        coordinator.activate(generation)
        coordinator.create(request("own-offer", "own", owner = session))
        coordinator.create(request("foreign-offer", "foreign", owner = otherSession))
        coordinator.transition(FileTransferId("own"), FileTransferEvent.Started)
        coordinator.transition(
            FileTransferId("own"),
            FileTransferEvent.Progressed(bytesTransferred = 1, speedBytesPerSecond = 10),
        )

        val first = coordinator.snapshotFor(generation, session.id)
        val refreshed = coordinator.snapshotFor(generation, session.id)

        assertEquals(listOf("own"), first.items.map { it.metadata.id.value })
        assertEquals(first, refreshed)
        assertEquals(1, refreshed.items.distinctBy { it.metadata.id }.size)
        assertEquals(FileTransferPhase.TRANSFERRING, refreshed.items.single().phase)
        assertEquals(1, refreshed.items.single().bytesTransferred)
        assertEquals(FileTransferPhase.TRANSFERRING, coordinator.state.value.item(FileTransferId("own"))?.phase)
    }

    @Test
    fun explicitRetryRestartsOnlyFailedOrCancelledItemForAnActiveOwner() = runTest {
        val coordinator = coordinator()
        coordinator.activate(generation)
        coordinator.create(request("retry-offer", "retry-me"))
        coordinator.transition(FileTransferId("retry-me"), FileTransferEvent.Started)
        coordinator.onNetworkFailure(FileTransferId("retry-me"))

        assertEquals(
            FileTransferOperationResult.Accepted,
            coordinator.retry(FileTransferId("retry-me")),
        )
        assertEquals(
            FileTransferPhase.CONNECTING,
            coordinator.state.value.item(FileTransferId("retry-me"))?.phase,
        )
        assertEquals(
            FileTransferOperationResult.InvalidState,
            coordinator.retry(FileTransferId("retry-me")),
        )

        coordinator.cancel(FileTransferId("retry-me"))
        sessions = activeSessions()
        assertEquals(
            FileTransferOperationResult.Rejected(FileTransferFailure.SessionUnavailable),
            coordinator.retry(FileTransferId("retry-me")),
        )
    }

    private fun coordinator(maxItems: Int = 100) = FileTransferCoordinator(
        browserSessionState = { sessions },
        maxItems = maxItems,
    )

    private fun request(
        commandId: String,
        vararg transferIds: String,
        owner: BrowserSession = session,
    ) = CreateFileTransfersRequest(
        commandId = FileCommandId(commandId),
        generationId = generation,
        ownerSessionId = owner.id,
        files = transferIds.map(::metadata),
    )

    private fun metadata(id: String) = FileTransferMetadata(
        id = FileTransferId(id),
        displayName = "$id.bin",
        sizeBytes = 1,
        mimeType = "application/octet-stream",
        sha256 = "a".repeat(64),
        direction = FileTransferDirection.BROWSER_TO_ANDROID,
    )

    private fun activeSessions(vararg active: BrowserSession): BrowserSessionState =
        BrowserSessionState.active(
            generationId = generation,
            pairingCode = PairingCodeState("123456", 60_000),
            sessions = active.toList(),
        )

    private fun session(id: String) = BrowserSession(
        id = BrowserSessionId(id),
        generationId = generation,
        browserLabel = "Chrome",
        sourceIpv4 = "192.168.1.3",
        connectedAtElapsedRealtimeMs = 2,
    )
}
