package ru.hznik.devicebridge.web

import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.file.FileUploadTarget
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata

class RawFileUploadProcessorTest {

    @Test
    fun streamsValidPayloadCommitsAndReportsMonotonicProgress() = runTest {
        val bytes = "hello".encodeToByteArray()
        val target = FakeTarget()
        val progress = mutableListOf<Long>()

        val result = RawFileUploadProcessor(bufferSize = 2).receive(
            channel = ByteReadChannel(bytes),
            metadata = metadata(bytes.size.toLong(), sha256(bytes)),
            declaredContentLength = bytes.size.toLong(),
            target = target,
            onProgress = progress::add,
        )

        assertEquals(RawFileUploadResult.Completed, result)
        assertArrayEquals(bytes, target.output.toByteArray())
        assertEquals(1, target.commits)
        assertEquals(0, target.aborts)
        assertEquals(bytes.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun rejectsMissingLengthPrematureEofOversizeAndChecksumMismatchWithCleanup() = runTest {
        val bytes = "hello".encodeToByteArray()
        suspend fun execute(
            declared: Long?,
            metadataSize: Long,
            payload: ByteArray = bytes,
            hash: String = sha256(bytes),
        ): Pair<RawFileUploadResult, FakeTarget> {
            val target = FakeTarget()
            return RawFileUploadProcessor(bufferSize = 2).receive(
                channel = ByteReadChannel(payload),
                metadata = metadata(metadataSize, hash),
                declaredContentLength = declared,
                target = target,
            ) to target
        }

        val missing = execute(null, 5)
        val eof = execute(5, 5, "hey".encodeToByteArray())
        val oversize = execute(6, 5, "hello!".encodeToByteArray())
        val checksum = execute(5, 5, hash = "0".repeat(64))

        assertEquals(RawFileUploadResult.InvalidContentLength, missing.first)
        assertEquals(RawFileUploadResult.PrematureEof, eof.first)
        assertEquals(RawFileUploadResult.Oversize, oversize.first)
        assertEquals(RawFileUploadResult.ChecksumMismatch, checksum.first)
        listOf(missing, eof, oversize, checksum).forEach { (_, target) ->
            assertEquals(0, target.commits)
            assertEquals(1, target.aborts)
        }
    }

    @Test
    fun classifiesNoSpaceWriteFailureAndAbortsPartialOutput() = runTest {
        val target = NoSpaceTarget()
        val result = RawFileUploadProcessor(bufferSize = 2).receive(
            channel = ByteReadChannel("hello".encodeToByteArray()),
            metadata = metadata(5, sha256("hello".encodeToByteArray())),
            declaredContentLength = 5,
            target = target,
        )

        assertEquals(RawFileUploadResult.InsufficientSpace, result)
        assertEquals(1, target.aborts)
    }

    private class NoSpaceTarget : FileUploadTarget {
        var aborts = 0
        override fun outputStream() = object : OutputStream() {
            override fun write(value: Int) {
                throw IOException("ENOSPC: no space left on device")
            }
        }
        override suspend fun commit() = Unit
        override suspend fun abort() { aborts += 1 }
        override suspend fun close() = Unit
    }

    private class FakeTarget : FileUploadTarget {
        val output = ByteArrayOutputStream()
        var commits = 0
        var aborts = 0

        override fun outputStream() = output
        override suspend fun commit() { commits += 1 }
        override suspend fun abort() { aborts += 1 }
        override suspend fun close() = Unit
    }

    private fun metadata(size: Long, hash: String) = FileTransferMetadata(
        id = FileTransferId("upload-1"),
        displayName = "safe.bin",
        sizeBytes = size,
        mimeType = "application/octet-stream",
        sha256 = hash,
        direction = FileTransferDirection.BROWSER_TO_ANDROID,
    )

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
