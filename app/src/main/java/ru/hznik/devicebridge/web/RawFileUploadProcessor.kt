package ru.hznik.devicebridge.web

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.hznik.devicebridge.core.text.toLowerHex
import ru.hznik.devicebridge.data.file.FileUploadTarget
import ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES
import ru.hznik.devicebridge.domain.file.FileTransferMetadata

enum class RawFileUploadResult {
    Completed,
    InvalidContentLength,
    PrematureEof,
    Oversize,
    ChecksumMismatch,
    InsufficientSpace,
    Failed,
}

/** What was actually received for the whole file: its length and SHA-256 as lowercase hex. */
data class ReceivedFileDigest(
    val sizeBytes: Long,
    val sha256: String,
)

open class RawFileUploadProcessor(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bufferSize: Int = 64 * 1024,
) {
    init {
        require(bufferSize in 1..1024 * 1024)
    }

    /**
     * Writes the request body after the [FileUploadTarget.offsetBytes] already stored and checks
     * the SHA-256 of the whole file. An interrupted body keeps what was written for a later
     * resume; a wrong checksum or an oversized body deletes the output.
     *
     * Once the whole file has been read, [onReceived] gets the length and SHA-256 measured over
     * it (the stored prefix included) before they are compared with [metadata]; it is always
     * called before [RawFileUploadResult.Completed] is returned.
     */
    open suspend fun receive(
        channel: ByteReadChannel,
        metadata: FileTransferMetadata,
        declaredContentLength: Long?,
        target: FileUploadTarget,
        onProgress: suspend (Long) -> Unit = {},
        onReceived: (ReceivedFileDigest) -> Unit = {},
    ): RawFileUploadResult {
        var committed = false
        var keepPartial = true
        try {
            val offset = target.offsetBytes
            val remaining = metadata.sizeBytes - offset
            if (declaredContentLength == null || declaredContentLength < 0) {
                return RawFileUploadResult.InvalidContentLength
            }
            if (declaredContentLength > HARD_MAX_FILE_BYTES || declaredContentLength > remaining) {
                return RawFileUploadResult.Oversize
            }
            if (declaredContentLength != remaining) {
                return RawFileUploadResult.InvalidContentLength
            }

            val output = target.outputStream()
            val digest = target.digest()
            val buffer = ByteArray(bufferSize)
            var total = offset
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                total = Math.addExact(total, read.toLong())
                if (total > metadata.sizeBytes || total > HARD_MAX_FILE_BYTES) {
                    keepPartial = false
                    return RawFileUploadResult.Oversize
                }
                withContext(ioDispatcher) { output.write(buffer, 0, read) }
                digest.update(buffer, 0, read)
                onProgress(total)
            }
            if (total != metadata.sizeBytes) return RawFileUploadResult.PrematureEof
            val actualHash = digest.digest().toLowerHex()
            onReceived(ReceivedFileDigest(sizeBytes = total, sha256 = actualHash))
            if (!actualHash.equals(metadata.sha256, ignoreCase = true)) {
                keepPartial = false
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
        } catch (failure: Throwable) {
            return if (failure.isInsufficientSpace()) {
                RawFileUploadResult.InsufficientSpace
            } else {
                RawFileUploadResult.Failed
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + ioDispatcher) {
                if (!committed) {
                    runCatching { if (keepPartial) target.retain() else target.abort() }
                }
                runCatching { target.close() }
            }
        }
    }

    private fun Throwable.isInsufficientSpace(): Boolean =
        generateSequence(this) { throwable -> throwable.cause }
            .map { throwable -> throwable.message.orEmpty().uppercase() }
            .any { message ->
                message.contains("ENOSPC") ||
                    message.contains("NO SPACE LEFT") ||
                    message.contains("INSUFFICIENT SPACE")
            }
}
