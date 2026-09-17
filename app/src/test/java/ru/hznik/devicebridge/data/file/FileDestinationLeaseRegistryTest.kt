package ru.hznik.devicebridge.data.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferId

class FileDestinationLeaseRegistryTest {

    @Test
    fun replacementTerminalReleaseAndGenerationCleanupReleaseEachLeaseOnce() {
        val permissions = RecordingPermissions()
        val registry = FileDestinationLeaseRegistry()
        val transfer = FileTransferId("transfer")
        val other = FileTransferId("other")
        val first = ScopedDocumentTreeLease("content://first", 3, permissions)
        val replacement = ScopedDocumentTreeLease("content://replacement", 3, permissions)
        val otherLease = ScopedDocumentTreeLease("content://other", 3, permissions)

        registry.register(transfer, first)
        registry.register(transfer, replacement)
        registry.register(other, otherLease)
        registry.release(transfer)
        registry.release(transfer)
        registry.releaseAll()
        registry.releaseAll()

        assertEquals(
            listOf("content://first", "content://replacement", "content://other"),
            permissions.released,
        )
    }

    @Test
    fun longDocumentTreeUriIsKeptBehindShortTransferScopedIdentifier() {
        val permissions = RecordingPermissions()
        val registry = FileDestinationLeaseRegistry()
        val transfer = FileTransferId("incoming-file")
        val longTreeUri = "content://com.android.externalstorage.documents/tree/" +
            "primary%3ADeviceBridge%2FReceived%2F" + "nested%2F".repeat(20)
        val lease = ScopedDocumentTreeLease(longTreeUri, 3, permissions)

        val destinationId = registry.register(transfer, lease)

        assertEquals(FileDestinationId(transfer.value), destinationId)
        assertEquals(longTreeUri, registry.uri(transfer, destinationId))
        assertNull(registry.uri(transfer, FileDestinationId("another-transfer")))
    }

    private class RecordingPermissions : DocumentTreePermissionGateway {
        val released = mutableListOf<String>()
        override fun acquire(uri: String, grantFlags: Int) = true
        override fun isAvailable(uri: String) = true
        override fun release(uri: String, grantFlags: Int) { released += uri }
    }
}
