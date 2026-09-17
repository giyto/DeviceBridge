package ru.hznik.devicebridge.data.file

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferEvent
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.FileTransferState
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId

class FileTransferSchedulerTest {

    @Test
    fun sameDirectionUsesStableFifoWithOnlyOneActiveItem() = runTest {
        val scheduler = FileTransferScheduler()
        val first = transfer("first", FileTransferDirection.BROWSER_TO_ANDROID)
        val second = transfer("second", FileTransferDirection.BROWSER_TO_ANDROID)
        val third = transfer("third", FileTransferDirection.BROWSER_TO_ANDROID)

        scheduler.enqueue(listOf(first, second, third))

        assertEquals(
            listOf(
                "first" to FileTransferPhase.CONNECTING,
                "second" to FileTransferPhase.QUEUED,
                "third" to FileTransferPhase.QUEUED,
            ),
            scheduler.state.value.items.map { it.metadata.id.value to it.phase },
        )

        finish(scheduler, first.metadata.id)

        assertEquals(
            listOf(
                "first" to FileTransferPhase.COMPLETED,
                "second" to FileTransferPhase.CONNECTING,
                "third" to FileTransferPhase.QUEUED,
            ),
            scheduler.state.value.items.map { it.metadata.id.value to it.phase },
        )
    }

    @Test
    fun deliveredDownloadImmediatelyPromotesNextQueuedDownload() = runTest {
        val scheduler = FileTransferScheduler()
        val first = transfer("first", FileTransferDirection.ANDROID_TO_BROWSER)
        val second = transfer("second", FileTransferDirection.ANDROID_TO_BROWSER)
        scheduler.enqueue(listOf(first, second))

        scheduler.transition(first.metadata.id, FileTransferEvent.Started)
        scheduler.transition(first.metadata.id, FileTransferEvent.Progressed(1, 1))
        scheduler.transition(first.metadata.id, FileTransferEvent.Delivered)

        assertEquals(
            FileTransferPhase.COMPLETED,
            scheduler.state.value.item(first.metadata.id)?.phase,
        )
        assertEquals(
            FileTransferPhase.CONNECTING,
            scheduler.state.value.item(second.metadata.id)?.phase,
        )
    }

    @Test
    fun oppositeDirectionsMayBeActiveAtTheSameTime() = runTest {
        val scheduler = FileTransferScheduler()
        scheduler.enqueue(
            listOf(
                transfer("upload", FileTransferDirection.BROWSER_TO_ANDROID),
                transfer("download", FileTransferDirection.ANDROID_TO_BROWSER),
            ),
        )

        val active = scheduler.state.value.items.filter {
            it.phase == FileTransferPhase.CONNECTING
        }
        assertEquals(2, active.size)
        assertEquals(
            FileTransferDirection.entries.toSet(),
            active.map { it.metadata.direction }.toSet(),
        )
    }

    @Test
    fun queuedCancellationPromotesNothingAndDoesNotAffectActiveItem() = runTest {
        val scheduler = FileTransferScheduler()
        scheduler.enqueue(
            listOf(
                transfer("active", FileTransferDirection.BROWSER_TO_ANDROID),
                transfer("queued", FileTransferDirection.BROWSER_TO_ANDROID),
            ),
        )

        scheduler.transition(FileTransferId("queued"), FileTransferEvent.Cancelled)

        assertEquals(
            FileTransferPhase.CONNECTING,
            scheduler.state.value.item(FileTransferId("active"))?.phase,
        )
        assertEquals(
            FileTransferPhase.CANCELLED,
            scheduler.state.value.item(FileTransferId("queued"))?.phase,
        )
    }

    @Test
    fun schedulerWorkDoesNotBlockIndependentTextCoroutine() = runTest {
        val scheduler = FileTransferScheduler()
        val textDelivery = async { "text-delivered" }

        scheduler.enqueue(
            List(32) { index ->
                transfer("file-$index", FileTransferDirection.BROWSER_TO_ANDROID)
            },
        )

        assertEquals("text-delivered", textDelivery.await())
        assertTrue(scheduler.state.value.items.isNotEmpty())
    }

    @Test
    fun explicitRetryResetsTerminalItemAndReturnsItToTheDirectionQueue() = runTest {
        val scheduler = FileTransferScheduler()
        val failed = transfer("failed", FileTransferDirection.BROWSER_TO_ANDROID)
        scheduler.enqueue(listOf(failed))
        scheduler.transition(failed.metadata.id, FileTransferEvent.Started)
        scheduler.transition(
            failed.metadata.id,
            FileTransferEvent.Failed(ru.hznik.devicebridge.domain.file.FileTransferFailure.StreamFailed),
        )

        val retried = scheduler.retry(failed.metadata.id)

        assertEquals(FileTransferPhase.CONNECTING, retried?.phase)
        assertEquals(0L, retried?.bytesTransferred)
        assertEquals(null, retried?.failure)
    }

    private suspend fun finish(
        scheduler: FileTransferScheduler,
        id: FileTransferId,
    ) {
        scheduler.transition(id, FileTransferEvent.Started)
        scheduler.transition(id, FileTransferEvent.Progressed(1, 1))
        scheduler.transition(id, FileTransferEvent.Verifying)
        scheduler.transition(id, FileTransferEvent.Completed)
    }

    private fun transfer(
        id: String,
        direction: FileTransferDirection,
    ) = FileTransferState.queued(
        generationId = ServerGenerationId(1),
        ownerSessionId = BrowserSessionId("session-1"),
        metadata = FileTransferMetadata(
            id = FileTransferId(id),
            displayName = "$id.bin",
            sizeBytes = 1,
            mimeType = "application/octet-stream",
            sha256 = "a".repeat(64),
            direction = direction,
        ),
    )
}
