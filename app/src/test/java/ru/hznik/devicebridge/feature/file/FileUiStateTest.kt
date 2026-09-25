package ru.hznik.devicebridge.feature.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferPhase

class FileUiStateTest {

    @Test
    fun progressIsDeterminateOnlyWhileBytesAreActuallyTransferring() {
        val transferring = item(FileTransferPhase.TRANSFERRING, bytesTransferred = 50)
        val verifying = item(FileTransferPhase.VERIFYING, bytesTransferred = 100)
        val completed = item(FileTransferPhase.COMPLETED, bytesTransferred = 100)

        assertTrue(transferring.hasDeterminateProgress)
        assertEquals(50, transferring.percent)
        assertFalse(verifying.hasDeterminateProgress)
        assertNull(verifying.percent)
        assertFalse(completed.hasActiveProgress)
        assertNull(completed.percent)
    }

    // The card's progress math, read for one item.
    private val FileTransferItemUiState.hasDeterminateProgress: Boolean
        get() = determinateProgress(phase, sizeBytes, bytesTransferred) != null

    private val FileTransferItemUiState.percent: Int?
        get() = determinateProgress(phase, sizeBytes, bytesTransferred)?.let(::progressPercent)

    @Test
    fun keptPartsTurnRetryIntoContinueAndExplainWhatWasKept() {
        val mb = 1024L * 1024
        val upload = item(FileTransferPhase.FAILED, bytesTransferred = 0).copy(
            sizeBytes = 20 * mb,
            bytesTransferred = 5 * mb,
            failure = FileTransferFailure.StreamFailed,
            resumableBytes = 5 * mb,
        )
        assertTrue(upload.continuesUpload)
        assertEquals(
            "Передача прервалась. Сохранено ${formatBytes(5 * mb)} из ${formatBytes(20 * mb)}, её можно продолжить.",
            upload.failureMessage,
        )
        val noSpace = upload.copy(failure = FileTransferFailure.InsufficientSpace)
        assertTrue(noSpace.failureMessage!!.startsWith("На устройстве недостаточно свободного места. Сохранено"))

        val download = upload.copy(direction = FileTransferDirection.ANDROID_TO_BROWSER)
        assertFalse(download.continuesUpload)
        assertTrue(download.failureMessage!!.contains("возобновить её в течение 15 минут"))

        val fromScratch = upload.copy(resumableBytes = null)
        assertFalse(fromScratch.continuesUpload)
        assertTrue(fromScratch.failureMessage!!.startsWith("Передача прервалась. Незавершённый файл удалён"))

    }

    private fun item(
        phase: FileTransferPhase,
        bytesTransferred: Long,
    ) = FileTransferItemUiState(
        id = FileTransferId("file-1"),
        displayName = "report.bin",
        sizeBytes = 100,
        mimeType = "application/octet-stream",
        direction = FileTransferDirection.BROWSER_TO_ANDROID,
        phase = phase,
        bytesTransferred = bytesTransferred,
        speedBytesPerSecond = 10,
        failure = null,
    )
}
