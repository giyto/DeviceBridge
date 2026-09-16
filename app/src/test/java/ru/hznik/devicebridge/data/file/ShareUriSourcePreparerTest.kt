package ru.hznik.devicebridge.data.file

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareUriSourcePreparerTest {
    private val noBackupDirectory = Files.createTempDirectory("devicebridge-no-backup").toFile()

    @After
    fun cleanupDirectory() {
        noBackupDirectory.deleteRecursively()
    }

    @Test
    fun retainsOpenDescriptorWithoutCreatingStageFile() = runTest {
        val retained = RecordingInputStream(byteArrayOf(1, 2, 3))
        val gateway = FakeShareUriStreamGateway(retained = retained)
        val preparer = preparer(gateway)

        val result = preparer.prepare("content://share/one", 3, preferRetained = true)
        val source = (result as ShareSourcePreparation.Ready).source

        assertTrue(source is PreparedShareSource.Retained)
        assertTrue(noBackupDirectory.listFiles().orEmpty().isEmpty())
        source.cleanup()
        assertTrue(retained.closed)
    }

    @Test
    fun stagesFallbackOnlyInsideNoBackupDirectoryAndDeletesOnCleanup() = runTest {
        val gateway = FakeShareUriStreamGateway(
            staging = ByteArrayInputStream(byteArrayOf(4, 5, 6)),
        )
        val preparer = preparer(gateway)

        val result = preparer.prepare("content://share/two", 3, preferRetained = true)
        val source = (result as ShareSourcePreparation.Ready).source
        val staged = source as PreparedShareSource.Staged

        assertEquals(noBackupDirectory.canonicalFile, staged.file.parentFile?.canonicalFile)
        assertEquals(listOf<Byte>(4, 5, 6), staged.file.readBytes().toList())
        source.cleanup()
        assertFalse(staged.file.exists())
    }

    @Test
    fun rejectsInsufficientSpaceBeforeOpeningPayload() = runTest {
        val gateway = FakeShareUriStreamGateway(
            staging = ByteArrayInputStream(ByteArray(10)),
        )
        val preparer = preparer(gateway, usableBytes = 9)

        val result = preparer.prepare("content://share/large", 10, preferRetained = false)

        assertEquals(ShareSourcePreparation.InsufficientSpace, result)
        assertEquals(0, gateway.openAttempts)
        assertTrue(noBackupDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun reportsPermissionLossAndCleansPartialStage() = runTest {
        val gateway = FakeShareUriStreamGateway(permissionLost = true)
        val preparer = preparer(gateway)

        val result = preparer.prepare("content://share/revoked", 3, preferRetained = false)

        assertEquals(ShareSourcePreparation.PermissionLost, result)
        assertTrue(noBackupDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrownAfterPartialStageCleanup() = runTest {
        val cancellingInput = object : InputStream() {
            override fun read(): Int = throw CancellationException("cancel")
        }
        val gateway = FakeShareUriStreamGateway(staging = cancellingInput)
        val preparer = preparer(gateway)

        try {
            preparer.prepare("content://share/cancel", 3, preferRetained = false)
        } finally {
            assertTrue(noBackupDirectory.listFiles().orEmpty().isEmpty())
        }
    }

    private fun TestScope.preparer(
        gateway: FakeShareUriStreamGateway,
        usableBytes: Long = Long.MAX_VALUE,
    ) = ShareUriSourcePreparer(
        streamGateway = gateway,
        noBackupDirectory = noBackupDirectory,
        usableBytes = { usableBytes },
        copier = AndroidChunkedFileCopier(
            ioDispatcher = StandardTestDispatcher(testScheduler),
            bufferSize = 4,
        ),
    )

    private class FakeShareUriStreamGateway(
        private val retained: InputStream? = null,
        private val staging: InputStream? = null,
        private val permissionLost: Boolean = false,
    ) : ShareUriStreamGateway {
        var openAttempts = 0

        override fun openRetained(uri: String): InputStream? {
            openAttempts += 1
            if (permissionLost) throw SecurityException("revoked")
            return retained
        }

        override fun openForStaging(uri: String): InputStream? {
            openAttempts += 1
            if (permissionLost) throw SecurityException("revoked")
            return staging
        }
    }

    private class RecordingInputStream(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
