package ru.hznik.devicebridge.data.file

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SAF byte access; resuming needs a seekable descriptor, which local storage providers give. */
class ContentResolverDocumentIo(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DocumentIo {
    override suspend fun openFresh(documentUri: String): OutputStream? = withContext(ioDispatcher) {
        contentResolver.openOutputStream(Uri.parse(documentUri), "w")
    }

    override suspend fun openForResume(documentUri: String): ResumableDocument? =
        withContext(ioDispatcher) {
            val descriptor = runCatching {
                contentResolver.openFileDescriptor(Uri.parse(documentUri), "rw")
            }.getOrNull() ?: return@withContext null
            // Pipes and cloud streams have no position, so they cannot continue a partial.
            val seekable = runCatching {
                FileInputStream(descriptor.fileDescriptor).channel.position()
            }.isSuccess
            if (!seekable) {
                runCatching { descriptor.close() }
                return@withContext null
            }
            DescriptorDocument(descriptor)
        }
}

private class DescriptorDocument(
    private val descriptor: ParcelFileDescriptor,
) : ResumableDocument {
    override fun readPrefix(consumer: (ByteArray, Int) -> Unit): Long {
        val input = FileInputStream(descriptor.fileDescriptor)
        input.channel.position(0)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            consumer(buffer, read)
            total += read
        }
        return total
    }

    override fun outputAt(offset: Long): OutputStream {
        val output = FileOutputStream(descriptor.fileDescriptor)
        output.channel.truncate(offset)
        output.channel.position(offset)
        return object : OutputStream() {
            override fun write(b: Int) = output.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = output.write(b, off, len)
            override fun flush() = output.flush()
            override fun close() {
                try {
                    output.close()
                } finally {
                    descriptor.close()
                }
            }
        }
    }

    override fun close() = descriptor.close()
}
