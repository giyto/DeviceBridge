package ru.hznik.devicebridge.data.file

import ru.hznik.devicebridge.domain.file.FileTransferId

interface FileTransferWifiLock {
    fun acquire(transferId: FileTransferId)
    fun release(transferId: FileTransferId)
    fun releaseAll()
}

data object NoOpFileTransferWifiLock : FileTransferWifiLock {
    override fun acquire(transferId: FileTransferId) = Unit
    override fun release(transferId: FileTransferId) = Unit
    override fun releaseAll() = Unit
}

interface PlatformWifiLockHandle {
    val isHeld: Boolean
    fun acquire()
    fun release()
}

class ReferenceCountedFileTransferWifiLock(
    private val handle: PlatformWifiLockHandle,
) : FileTransferWifiLock {
    private val activeTransfers = linkedSetOf<FileTransferId>()

    @Synchronized
    override fun acquire(transferId: FileTransferId) {
        if (!activeTransfers.add(transferId)) return
        if (activeTransfers.size == 1 && !handle.isHeld) handle.acquire()
    }

    @Synchronized
    override fun release(transferId: FileTransferId) {
        if (!activeTransfers.remove(transferId)) return
        if (activeTransfers.isEmpty() && handle.isHeld) handle.release()
    }

    @Synchronized
    override fun releaseAll() {
        activeTransfers.clear()
        if (handle.isHeld) handle.release()
    }
}
