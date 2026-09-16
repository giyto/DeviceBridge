package ru.hznik.devicebridge.data.file

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileTransferId

class FileSourceRegistryTest {

    @Test
    fun stagedFallbackIsPrivateToRegistryAndDeletedOnRemoveOrGenerationClear() {
        val directory = Files.createTempDirectory("devicebridge-source-registry").toFile()
        try {
            val first = directory.resolve("first.stage").apply { writeText("first") }
            val second = directory.resolve("second.stage").apply { writeText("second") }
            val registry = FileSourceRegistry()
            val firstId = FileTransferId("first")
            val secondId = FileTransferId("second")

            registry.registerStaged(firstId, first)
            registry.registerStaged(secondId, second)
            assertEquals(first.canonicalFile, registry.stagedFile(firstId))
            assertNull(registry.sourceUri(firstId))

            registry.remove(firstId)
            assertFalse(first.exists())
            assertTrue(second.exists())

            registry.clear()
            assertFalse(second.exists())
            assertNull(registry.stagedFile(secondId))
        } finally {
            directory.deleteRecursively()
        }
    }
}
