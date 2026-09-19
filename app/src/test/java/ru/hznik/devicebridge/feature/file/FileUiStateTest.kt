package ru.hznik.devicebridge.feature.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferPhase

class FileUiStateTest {

    @Test
    fun progressIsDeterminateOnlyWhileBytesAreActuallyTransferring() {
        val transferring = item(FileTransferPhase.TRANSFERRING, bytesTransferred = 50)
        val verifying = item(FileTransferPhase.VERIFYING, bytesTransferred = 100)
        val completed = item(FileTransferPhase.COMPLETED, bytesTransferred = 100)

        assertTrue(transferring.hasDeterminateProgress)
        assertEquals(50, transferring.progressPercent)
        assertFalse(verifying.hasDeterminateProgress)
        assertNull(verifying.progressPercent)
        assertFalse(completed.hasActiveProgress)
        assertNull(completed.progressPercent)
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
