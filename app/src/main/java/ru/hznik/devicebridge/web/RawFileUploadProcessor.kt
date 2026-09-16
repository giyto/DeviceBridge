package ru.hznik.devicebridge.web

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.hznik.devicebridge.data.file.FileUploadTarget
import ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES
import ru.hznik.devicebridge.domain.file.FileTransferMetadata

enum class RawFileUploadResult {
    Completed,
    InvalidContentLength,
    PrematureEof,
    Oversize,
    ChecksumMismatch,
    Failed,
}

class RawFileUploadProcessor(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bufferSize: Int = 64 * 1024,
) {
    init {
        require(bufferSize in 1..1024 * 1024)
    }

    suspend fun receive(
        channel: ByteReadChannel,
        metadata: FileTransferMetadata,
        declaredContentLength: Long?,
        target: FileUploadTarget,
        onProgress: suspend (Long) -> Unit = {},
    ): RawFileUploadResult {
        var committed = false
        try {
            if (declaredContentLength == null || declaredContentLength < 0) {
                return RawFileUploadResult.InvalidContentLength
            }
            if (declaredContentLength > HARD_MAX_FILE_BYTES || declaredContentLength > metadata.sizeBytes) {
                return RawFileUploadResult.Oversize
            }
            if (declaredContentLength != metadata.sizeBytes) {
                return RawFileUploadResult.InvalidContentLength
            }

            val output = target.outputStream()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(bufferSize)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                total = Math.addExact(total, read.toLong())
                if (total > metadata.sizeBytes || total > HARD_MAX_FILE_BYTES) {
                    return RawFileUploadResult.Oversize
                }
                withContext(ioDispatcher) { output.write(buffer, 0, read) }
                digest.update(buffer, 0, read)
                onProgress(total)
            }
            if (total != metadata.sizeBytes) return RawFileUploadResult.PrematureEof
            val actualHash = digest.digest().toHex()
            if (!actualHash.equals(metadata.sha256, ignoreCase = true)) {
                return RawFileUploadResult.ChecksumMismatch
            }
            withContext(ioDispatcher) {
                output.flush()
                target.commit()
            }
            committed = true
            return RawFileUploadResult.Completed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return RawFileUploadResult.Failed
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + ioDispatcher) {
                if (!committed) runCatching { target.abort() }
                runCatching { target.close() }
            }
        }
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        this@toHex.forEach { byte -> append("%02x".format(byte)) }
    }
}
