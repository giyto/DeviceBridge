package ru.hznik.devicebridge.data.file

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidChunkedFileCopierTest {

    @Test
    fun copiesBytesComputesSha256AndReportsMonotonicProgress() = runTest {
        val bytes = "hello streaming".encodeToByteArray()
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val copier = AndroidChunkedFileCopier(
            ioDispatcher = StandardTestDispatcher(testScheduler),
            bufferSize = 4,
        )

        val result = copier.copy(ByteArrayInputStream(bytes), output) { copied ->
            progress += copied
        }

        assertEquals(bytes.toList(), output.toByteArray().toList())
        assertEquals(bytes.size.toLong(), result.sizeBytes)
        assertEquals(sha256(bytes), result.sha256)
        assertEquals(bytes.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
    }
    @Test
    fun usesFixedBoundedBufferForDeterministicLargeStream() = runTest {
        val input = DeterministicInputStream(5L * 1024 * 1024)
        val output = CountingOutputStream()
        val copier = AndroidChunkedFileCopier(
            ioDispatcher = StandardTestDispatcher(testScheduler),
            bufferSize = 8 * 1024,
        )

        val result = copier.copy(input, output)

        assertEquals(5L * 1024 * 1024, result.sizeBytes)
        assertEquals(result.sizeBytes, output.bytesWritten)
        assertTrue(input.maxRequestedBytes <= 8 * 1024)
    }

    @Test
    fun cancellationStopsCopyBeforeRemainingPayload() = runTest {
        val input = DeterministicInputStream(1024 * 1024)
        val output = CountingOutputStream()
        val copier = AndroidChunkedFileCopier(
            ioDispatcher = StandardTestDispatcher(testScheduler),
            bufferSize = 1024,
        )

        try {
            copier.copy(input, output) { throw CancellationException("cancelled") }
        } catch (_: CancellationException) {
            // Expected.
        }

        assertTrue(output.bytesWritten < 1024 * 1024)
    }

    private class DeterministicInputStream(
        private var remaining: Long,
    ) : InputStream() {
        var maxRequestedBytes = 0

        override fun read(): Int =
            if (remaining-- > 0) 0x5A else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            maxRequestedBytes = maxOf(maxRequestedBytes, length)
            val count = minOf(length.toLong(), remaining).toInt()
            buffer.fill(0x5A, offset, offset + count)
            remaining -= count
            return count
        }
    }

    private class CountingOutputStream : OutputStream() {
        var bytesWritten = 0L

        override fun write(value: Int) {
            bytesWritten += 1
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            bytesWritten += length
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
