package ru.hznik.devicebridge.data.file

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import ru.hznik.devicebridge.domain.file.FileTransferId

@Singleton
class FileDestinationLeaseRegistry @Inject constructor() {
    private val leases = ConcurrentHashMap<FileTransferId, ScopedDocumentTreeLease>()

    fun register(transferId: FileTransferId, lease: ScopedDocumentTreeLease) {
        leases.put(transferId, lease)?.release()
    }

    fun release(transferId: FileTransferId) {
        leases.remove(transferId)?.release()
    }

    fun releaseAll() {
        val current = leases.values.toList()
        leases.clear()
        current.forEach(ScopedDocumentTreeLease::release)
    }
}
