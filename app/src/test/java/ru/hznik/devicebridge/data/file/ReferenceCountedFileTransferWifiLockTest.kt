package ru.hznik.devicebridge.data.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileTransferId

class ReferenceCountedFileTransferWifiLockTest {

    @Test
    fun acquiresPlatformLockForFirstTransferAndReleasesAfterLastTransfer() {
        val handle = RecordingWifiLockHandle()
        val lock = ReferenceCountedFileTransferWifiLock(handle)
        val upload = FileTransferId("upload")
        val download = FileTransferId("download")

        lock.acquire(upload)
        lock.acquire(upload)
        lock.acquire(download)

        assertEquals(1, handle.acquireCalls)
        assertTrue(handle.isHeld)

        lock.release(upload)
        assertEquals(0, handle.releaseCalls)
        assertTrue(handle.isHeld)

        lock.release(download)
        assertEquals(1, handle.releaseCalls)
        assertFalse(handle.isHeld)
    }

    @Test
    fun releaseAllIsIdempotentAndAllowsCleanNextCycle() {
        val handle = RecordingWifiLockHandle()
        val lock = ReferenceCountedFileTransferWifiLock(handle)

        lock.acquire(FileTransferId("first"))
        lock.acquire(FileTransferId("second"))
        lock.releaseAll()
        lock.releaseAll()
        lock.acquire(FileTransferId("next"))
        lock.releaseAll()

        assertEquals(2, handle.acquireCalls)
        assertEquals(2, handle.releaseCalls)
        assertFalse(handle.isHeld)
    }

    private class RecordingWifiLockHandle : PlatformWifiLockHandle {
        var acquireCalls = 0
        var releaseCalls = 0
        override var isHeld: Boolean = false

        override fun acquire() {
            acquireCalls += 1
            isHeld = true
        }

        override fun release() {
            releaseCalls += 1
            isHeld = false
        }
    }
}
