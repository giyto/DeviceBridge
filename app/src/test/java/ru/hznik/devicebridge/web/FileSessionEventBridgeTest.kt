package ru.hznik.devicebridge.web

import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.file.FileSourceRegistry
import ru.hznik.devicebridge.data.file.FileTransferCoordinator
import ru.hznik.devicebridge.data.session.SessionEventDispatcher
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileCommandId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferEvent
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingCodeState
import ru.hznik.devicebridge.domain.session.ServerGenerationId

@OptIn(ExperimentalCoroutinesApi::class)
class FileSessionEventBridgeTest {

    @Test
    fun cancelledSourceIsRetainedForRetryAndDeletedAfterCompletion() = runTest {
        val generation = ServerGenerationId(1)
        val session = BrowserSession(
            id = BrowserSessionId("session-1"),
            generationId = generation,
            browserLabel = "Chrome",
            sourceIpv4 = "192.168.1.2",
            connectedAtElapsedRealtimeMs = 1,
        )
        val sessionState = MutableStateFlow(activeState(generation, listOf(session)))
        val files = FileTransferCoordinator(browserSessionState = { sessionState.value })
        files.activate(generation)
        val transferId = FileTransferId("file-staged")
        files.create(
            CreateFileTransfersRequest(
                commandId = FileCommandId("command-staged"),
                generationId = generation,
                ownerSessionId = session.id,
                files = listOf(
                    FileTransferMetadata(
                        id = transferId,
                        displayName = "shared.bin",
                        sizeBytes = 1,
                        mimeType = "application/octet-stream",
                        sha256 = "a".repeat(64),
                        direction = FileTransferDirection.ANDROID_TO_BROWSER,
                    ),
                ),
            ),
        )
        val registry = FileSourceRegistry()
        val stage = Files.createTempFile("devicebridge-bridge", ".stage").toFile()
            .apply { writeBytes(byteArrayOf(1)) }
        registry.registerStaged(transferId, stage)
        assertTrue("staged source should exist after registration", stage.exists())
        val bridge = FileSessionEventBridge(
            scope = this,
            coordinator = files,
            dispatcher = SessionEventDispatcher(this),
            wallClockMs = { 1_000 },
            browserSessionState = sessionState,
            sourceRegistry = registry,
        )
        runCurrent()
        assertTrue("initial observation must not delete the staged source", stage.exists())

        files.cancel(transferId)
        runCurrent()

        assertTrue("cancellation must retain the staged source for retry", stage.exists())
        files.retry(transferId)
        files.transition(transferId, FileTransferEvent.Started)
        files.transition(transferId, FileTransferEvent.Progressed(1, 1))
        files.transition(transferId, FileTransferEvent.Delivered)
        runCurrent()

        assertFalse(stage.exists())
        bridge.close()
    }

    @Test
    fun observedSessionRevocationCancelsItsRemainingFileTransfers() = runTest {
        val generation = ServerGenerationId(1)
        val session = BrowserSession(
            id = BrowserSessionId("session-1"),
            generationId = generation,
            browserLabel = "Chrome",
            sourceIpv4 = "192.168.1.2",
            connectedAtElapsedRealtimeMs = 1,
        )
        val sessionState = MutableStateFlow(activeState(generation, listOf(session)))
        val files = FileTransferCoordinator(browserSessionState = { sessionState.value })
        files.activate(generation)
        files.create(
            CreateFileTransfersRequest(
                commandId = FileCommandId("command-1"),
                generationId = generation,
                ownerSessionId = session.id,
                files = listOf(
                    FileTransferMetadata(
                        id = FileTransferId("file-1"),
                        displayName = "report.bin",
                        sizeBytes = 1,
                        mimeType = "application/octet-stream",
                        sha256 = "a".repeat(64),
                        direction = FileTransferDirection.ANDROID_TO_BROWSER,
                    ),
                ),
            ),
        )
        val transferId = FileTransferId("file-1")
        val registry = FileSourceRegistry()
        val stage = Files.createTempFile("devicebridge-revoke", ".stage").toFile()
            .apply { writeBytes(byteArrayOf(1)) }
        registry.registerStaged(transferId, stage)
        val bridge = FileSessionEventBridge(
            scope = this,
            coordinator = files,
            dispatcher = SessionEventDispatcher(this),
            wallClockMs = { 1_000 },
            browserSessionState = sessionState,
            sourceRegistry = registry,
        )
        runCurrent()

        sessionState.value = activeState(generation, emptyList())
        runCurrent()

        assertEquals(
            FileTransferPhase.CANCELLED,
            files.state.value.item(transferId)?.phase,
        )
        assertFalse(stage.exists())
        bridge.close()
    }

    private fun activeState(
        generation: ServerGenerationId,
        sessions: List<BrowserSession>,
    ) = BrowserSessionState.active(
        generationId = generation,
        pairingCode = PairingCodeState("123456", 60_000),
        sessions = sessions,
    )
}
