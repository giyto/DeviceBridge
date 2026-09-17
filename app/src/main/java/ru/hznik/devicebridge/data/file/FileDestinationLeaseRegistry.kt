package ru.hznik.devicebridge.data.file

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferId

@Singleton
class FileDestinationLeaseRegistry @Inject constructor() {
    private val leases = ConcurrentHashMap<FileTransferId, RegisteredDestination>()

    fun register(
        transferId: FileTransferId,
        lease: ScopedDocumentTreeLease,
    ): FileDestinationId {
        val destinationId = FileDestinationId(transferId.value)
        leases.put(
            transferId,
            RegisteredDestination(destinationId, lease),
        )?.lease?.release()
        return destinationId
    }

    fun uri(
        transferId: FileTransferId,
        destinationId: FileDestinationId,
    ): String? = leases[transferId]
        ?.takeIf { destination -> destination.id == destinationId }
        ?.lease
        ?.takeIf(ScopedDocumentTreeLease::isAvailable)
        ?.uri

    fun release(transferId: FileTransferId) {
        leases.remove(transferId)?.lease?.release()
    }

    fun releaseAll() {
        val current = leases.values.map(RegisteredDestination::lease)
        leases.clear()
        current.forEach(ScopedDocumentTreeLease::release)
    }

    private data class RegisteredDestination(
        val id: FileDestinationId,
        val lease: ScopedDocumentTreeLease,
    )
}
