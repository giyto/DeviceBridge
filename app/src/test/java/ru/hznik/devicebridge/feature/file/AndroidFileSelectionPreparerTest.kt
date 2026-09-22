package ru.hznik.devicebridge.feature.file

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.file.AndroidChunkedFileCopier
import ru.hznik.devicebridge.data.file.AndroidDocumentMetadata
import ru.hznik.devicebridge.data.file.AndroidFileSourcePickerGateway
import ru.hznik.devicebridge.data.file.FileSourceRegistry
import ru.hznik.devicebridge.domain.file.FileDraftId
import ru.hznik.devicebridge.domain.file.FileTransferId

class AndroidFileSelectionPreparerTest {

    @Test
    fun temporaryShareIsStreamedIntoPrivateStageAndRegisteredWithoutSourceUri() = runTest {
        val fixture = Fixture(bytes = "shared payload".encodeToByteArray())
        try {
            val result = fixture.preparer().prepare(
                uris = listOf(fixture.uri),
                stageTemporarySources = true,
            )

            assertEquals(0, result.rejectedCount)
            val item = result.items.single()
            val staged = fixture.registry.draftStagedFile(item.id)!!
            assertNull(fixture.registry.draftSourceUri(item.id))
            assertTrue(staged.canonicalPath.startsWith(fixture.directory.canonicalPath))
            assertArrayEquals(fixture.bytes, staged.readBytes())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun temporaryShareIsStagedWhenStagingDirectoryDoesNotExistYet() = runTest {
        val fixture = Fixture(
            bytes = "first share after install".encodeToByteArray(),
            stageDirectoryExists = false,
            realUsableSpace = true,
        )
        try {
            assertFalse(fixture.directory.exists())

            val result = fixture.preparer().prepare(
                uris = listOf(fixture.uri),
                stageTemporarySources = true,
            )

            assertEquals(0, result.rejectedCount)
            val staged = fixture.registry.draftStagedFile(result.items.single().id)!!
            assertArrayEquals(fixture.bytes, staged.readBytes())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun regularPickerKeepsScopedContentUriWithoutCreatingStage() = runTest {
        val fixture = Fixture(bytes = byteArrayOf(1, 2, 3))
        try {
            val result = fixture.preparer().prepare(listOf(fixture.uri))

            val item = result.items.single()
            assertEquals(fixture.uri, fixture.registry.draftSourceUri(item.id))
            assertNull(fixture.registry.draftStagedFile(item.id))
            assertTrue(fixture.directory.listFiles().orEmpty().isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun sourceLeasePromotesOnlyOnConfirmAndCanRollbackWithoutDeletingSource() = runTest {
        val fixture = Fixture(bytes = byteArrayOf(1, 2, 3))
        try {
            val item = fixture.preparer().prepare(listOf(fixture.uri)).items.single()
            val transferId = FileTransferId("confirmed-transfer")

            assertNull(fixture.registry.sourceUri(transferId))
            assertTrue(item.sourceLease.promote(transferId))
            assertNull(fixture.registry.draftSourceUri(item.id))
            assertEquals(fixture.uri, fixture.registry.sourceUri(transferId))

            item.sourceLease.rollback(transferId)
            assertNull(fixture.registry.sourceUri(transferId))
            assertEquals(fixture.uri, fixture.registry.draftSourceUri(item.id))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun temporaryShareIsRejectedBeforeReadWhenStageHasInsufficientSpace() = runTest {
        val fixture = Fixture(bytes = byteArrayOf(1, 2, 3), availableBytes = 2)
        try {
            val result = fixture.preparer().prepare(
                uris = listOf(fixture.uri),
                stageTemporarySources = true,
            )

            assertEquals(1, result.rejectedCount)
            assertTrue(result.items.isEmpty())
            assertFalse(fixture.wasOpened)
            assertTrue(fixture.directory.listFiles().orEmpty().isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun effectiveLimitRejectsSelectionBeforeOpeningItsPayload() = runTest {
        val fixture = Fixture(bytes = byteArrayOf(1, 2, 3))
        try {
            val result = fixture.preparer().prepare(
                uris = listOf(fixture.uri),
                effectiveFileLimitBytes = 2,
            )

            assertEquals(1, result.rejectedCount)
            assertTrue(result.items.isEmpty())
            assertFalse(fixture.wasOpened)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun permissionLossOrSizeMismatchLeavesNoTemporaryPayload() = runTest {
        val permissionLoss = Fixture(bytes = byteArrayOf(1), openAllowed = false)
        val sizeMismatch = Fixture(bytes = byteArrayOf(1), declaredSize = 2)
        try {
            val lost = permissionLoss.preparer().prepare(
                listOf(permissionLoss.uri),
                stageTemporarySources = true,
            )
            val mismatched = sizeMismatch.preparer().prepare(
                listOf(sizeMismatch.uri),
                stageTemporarySources = true,
            )

            assertEquals(1, lost.rejectedCount)
            assertEquals(1, mismatched.rejectedCount)
            assertTrue(permissionLoss.directory.listFiles().orEmpty().isEmpty())
            assertTrue(sizeMismatch.directory.listFiles().orEmpty().isEmpty())
        } finally {
            permissionLoss.close()
            sizeMismatch.close()
        }
    }

    @Test
    fun startupCleanupDeletesOnlyOrphanStageFiles() {
        val directory = Files.createTempDirectory("devicebridge-orphan-stage").toFile()
        val registry = FileSourceRegistry()
        val retained = directory.resolve("source-retained.devicebridge-stage")
        val orphan = directory.resolve("source-orphan.devicebridge-stage")
        retained.writeBytes(byteArrayOf(1))
        orphan.writeBytes(byteArrayOf(2))
        registry.registerDraftStaged(FileDraftId("retained"), retained)
        try {
            AndroidFileSelectionPreparer(
                pickerGateway = AndroidFileSourcePickerGateway { null },
                copier = AndroidChunkedFileCopier(),
                sourceRegistry = registry,
                stagingDirectory = directory,
                openInputStream = { null },
                availableBytes = { Long.MAX_VALUE },
            )

            assertTrue(retained.exists())
            assertFalse(orphan.exists())
        } finally {
            registry.clear()
            directory.deleteRecursively()
        }
    }

    private class Fixture(
        val bytes: ByteArray,
        private val declaredSize: Long = bytes.size.toLong(),
        private val availableBytes: Long = Long.MAX_VALUE,
        private val openAllowed: Boolean = true,
        stageDirectoryExists: Boolean = true,
        private val realUsableSpace: Boolean = false,
    ) {
        val uri = "content://fixture/shared"
        private val root = Files.createTempDirectory("devicebridge-selection-stage").toFile()
        val directory = if (stageDirectoryExists) root else root.resolve("file-sources")
        val registry = FileSourceRegistry()
        var wasOpened = false
            private set

        fun preparer() = AndroidFileSelectionPreparer(
            pickerGateway = AndroidFileSourcePickerGateway {
                AndroidDocumentMetadata("shared.bin", declaredSize, "application/octet-stream")
            },
            copier = AndroidChunkedFileCopier(),
            sourceRegistry = registry,
            stagingDirectory = directory,
            openInputStream = {
                wasOpened = true
                if (openAllowed) ByteArrayInputStream(bytes) else null
            },
            availableBytes = if (realUsableSpace) File::getUsableSpace else { _ -> availableBytes },
        )

        fun close() {
            registry.clear()
            root.deleteRecursively()
        }
    }
}
