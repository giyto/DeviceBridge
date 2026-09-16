package ru.hznik.devicebridge.domain.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId

class FileTransferReducerTest {

    @Test
    fun reducerAcceptsTheOrderedHappyPathWithMonotonicProgress() {
        val queued = transfer()
        val connecting = FileTransferReducer.reduce(queued, FileTransferEvent.Connecting)
        val transferring = FileTransferReducer.reduce(connecting, FileTransferEvent.Started)
        val halfway = FileTransferReducer.reduce(
            transferring,
            FileTransferEvent.Progressed(bytesTransferred = 256, speedBytesPerSecond = 128),
        )
        val sent = FileTransferReducer.reduce(
            halfway,
            FileTransferEvent.Progressed(bytesTransferred = 512, speedBytesPerSecond = 256),
        )
        val verifying = FileTransferReducer.reduce(sent, FileTransferEvent.Verifying)
        val completed = FileTransferReducer.reduce(verifying, FileTransferEvent.Completed)

        assertEquals(FileTransferPhase.CONNECTING, connecting.phase)
        assertEquals(FileTransferPhase.TRANSFERRING, halfway.phase)
        assertEquals(256, halfway.bytesTransferred)
        assertEquals(128, halfway.speedBytesPerSecond)
        assertEquals(FileTransferPhase.VERIFYING, verifying.phase)
        assertEquals(FileTransferPhase.COMPLETED, completed.phase)
    }

    @Test
    fun reducerRejectsSkippedStagesDecreasingBytesAndPrematureVerification() {
        val queued = transfer()
        assertSame(
            queued,
            FileTransferReducer.reduce(queued, FileTransferEvent.Verifying),
        )

        val transferring = FileTransferReducer.reduce(
            FileTransferReducer.reduce(queued, FileTransferEvent.Connecting),
            FileTransferEvent.Started,
        )
        val progressed = FileTransferReducer.reduce(
            transferring,
            FileTransferEvent.Progressed(300, 20),
        )
        assertSame(
            progressed,
            FileTransferReducer.reduce(progressed, FileTransferEvent.Progressed(299, 20)),
        )
        assertSame(
            progressed,
            FileTransferReducer.reduce(progressed, FileTransferEvent.Progressed(513, 20)),
        )
        assertSame(
            progressed,
            FileTransferReducer.reduce(progressed, FileTransferEvent.Verifying),
        )
    }

    @Test
    fun terminalStateIsImmutableAndOwnershipNeverChanges() {
        val initial = transfer()
        val owner = initial.ownerSessionId
        val generation = initial.generationId
        val cancelled = FileTransferReducer.reduce(initial, FileTransferEvent.Cancelled)

        assertEquals(FileTransferPhase.CANCELLED, cancelled.phase)
        assertEquals(owner, cancelled.ownerSessionId)
        assertEquals(generation, cancelled.generationId)
        assertSame(
            cancelled,
            FileTransferReducer.reduce(cancelled, FileTransferEvent.Connecting),
        )
        assertSame(
            cancelled,
            FileTransferReducer.reduce(
                cancelled,
                FileTransferEvent.Failed(FileTransferFailure.StreamFailed),
            ),
        )
    }

    @Test
    fun failureAndCancellationAreAllowedFromEveryNonTerminalStage() {
        val states = mutableListOf(transfer())
        states += FileTransferReducer.reduce(states.last(), FileTransferEvent.Connecting)
        states += FileTransferReducer.reduce(states.last(), FileTransferEvent.Started)
        states += FileTransferReducer.reduce(
            states.last(),
            FileTransferEvent.Progressed(512, 1),
        )
        states += FileTransferReducer.reduce(states.last(), FileTransferEvent.Verifying)

        states.forEach { state ->
            assertEquals(
                FileTransferPhase.CANCELLED,
                FileTransferReducer.reduce(state, FileTransferEvent.Cancelled).phase,
            )
            val failed = FileTransferReducer.reduce(
                state,
                FileTransferEvent.Failed(FileTransferFailure.ChecksumMismatch),
            )
            assertEquals(FileTransferPhase.FAILED, failed.phase)
            assertEquals(FileTransferFailure.ChecksumMismatch, failed.failure)
        }
    }

    private fun transfer() = FileTransferState.queued(
        generationId = ServerGenerationId(7),
        ownerSessionId = BrowserSessionId("session-1"),
        metadata = FileTransferMetadata(
            id = FileTransferId("transfer-1"),
            displayName = "photo.jpg",
            sizeBytes = 512,
            mimeType = "image/jpeg",
            sha256 = "a".repeat(64),
            direction = FileTransferDirection.BROWSER_TO_ANDROID,
        ),
    )
}
