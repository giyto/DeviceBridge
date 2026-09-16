package ru.hznik.devicebridge.data.file

import org.junit.Assert.assertEquals
import org.junit.Test
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

    private class RecordingPermissions : DocumentTreePermissionGateway {
        val released = mutableListOf<String>()
        override fun acquire(uri: String, grantFlags: Int) = true
        override fun isAvailable(uri: String) = true
        override fun release(uri: String, grantFlags: Int) { released += uri }
    }
}
