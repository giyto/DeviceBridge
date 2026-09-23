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

    /** Registers [lease] only when no destination is registered yet; returns null otherwise. */
    fun registerIfAbsent(
        transferId: FileTransferId,
        lease: ScopedDocumentTreeLease,
    ): FileDestinationId? {
        val destinationId = FileDestinationId(transferId.value)
        val previous = leases.putIfAbsent(
            transferId,
            RegisteredDestination(destinationId, lease),
        )
        return destinationId.takeIf { previous == null }
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

    /** Releases the registration only if it still holds this exact [lease]. */
    fun release(transferId: FileTransferId, lease: ScopedDocumentTreeLease) {
        val registered = RegisteredDestination(FileDestinationId(transferId.value), lease)
        if (leases.remove(transferId, registered)) lease.release()
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
