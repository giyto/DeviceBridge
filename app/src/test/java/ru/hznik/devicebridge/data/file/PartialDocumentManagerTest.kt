package ru.hznik.devicebridge.data.file

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialDocumentManagerTest {

    @Test
    fun createsCollisionFreePartialAndFinalizesWithRename() = runTest {
        val provider = FakeDocumentProvider(
            names = mutableSetOf("photo.jpg", "photo (1).jpg"),
        )
        val manager = PartialDocumentManager(provider)

        val handle = manager.create(
            treeUri = TREE,
            requestedName = "photo.jpg",
            fallbackId = "transfer-1",
        )
        val result = manager.finalize(handle)

        assertEquals("photo (2).jpg", handle.finalDisplayName)
        assertTrue(handle.partialDisplayName.endsWith(".devicebridge-partial"))
        assertEquals(
            PartialDocumentFinalization.Completed("content://docs/final"),
            result,
        )
        assertEquals(listOf(handle.partialDisplayName), provider.createdNames)
        // Never the file's own type: providers would append ".jpg" and galleries would show it.
        assertEquals(listOf("application/octet-stream"), provider.createdMimeTypes)
        assertEquals(listOf(handle.documentUri to "photo (2).jpg"), provider.renames)
    }

    @Test
    fun cancellationAndErrorDeletePartialDocument() = runTest {
        val provider = FakeDocumentProvider()
        val manager = PartialDocumentManager(provider)
        val cancelled = manager.create(TREE, "cancel.bin", "one")
        val failed = manager.create(TREE, "failed.bin", "two")

        assertEquals(PartialDocumentCleanup.Deleted, manager.cleanup(cancelled))
        assertEquals(PartialDocumentCleanup.Deleted, manager.cleanup(failed))
        assertEquals(listOf(cancelled.documentUri, failed.documentUri), provider.deleted)
    }

    @Test
    fun reportsVisiblePartialMarkerWhenProviderCannotRenameOrDelete() = runTest {
        val provider = FakeDocumentProvider(renameSucceeds = false, deleteSucceeds = false)
        val manager = PartialDocumentManager(provider)
        val handle = manager.create(TREE, "report.pdf", "three")

        val finalization = manager.finalize(handle)
        val cleanup = manager.cleanup(handle)

        assertEquals(
            PartialDocumentFinalization.PartialRemains(
                uri = handle.documentUri,
                displayName = handle.partialDisplayName,
            ),
            finalization,
        )
        assertEquals(
            PartialDocumentCleanup.PartialRemains(
                uri = handle.documentUri,
                displayName = handle.partialDisplayName,
            ),
            cleanup,
        )
    }

    private class FakeDocumentProvider(
        private val names: MutableSet<String> = mutableSetOf(),
        private val renameSucceeds: Boolean = true,
        private val deleteSucceeds: Boolean = true,
    ) : PartialDocumentProvider {
        val createdNames = mutableListOf<String>()
        val createdMimeTypes = mutableListOf<String>()
        val renames = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()
        private var nextId = 0

        override suspend fun listDisplayNames(treeUri: String): Set<String> = names.toSet()

        override suspend fun create(
            treeUri: String,
            mimeType: String,
            displayName: String,
        ): String? {
            createdNames += displayName
            createdMimeTypes += mimeType
            names += displayName
            nextId += 1
            return "content://docs/partial-" + nextId
        }

        override suspend fun rename(documentUri: String, displayName: String): String? {
            renames += documentUri to displayName
            return if (renameSucceeds) "content://docs/final" else null
        }

        override suspend fun delete(documentUri: String): Boolean {
            deleted += documentUri
            return deleteSucceeds
        }

        override suspend fun size(documentUri: String): Long? = null
    }

    private companion object {
        const val TREE = "content://docs/tree/folder"
    }
}
