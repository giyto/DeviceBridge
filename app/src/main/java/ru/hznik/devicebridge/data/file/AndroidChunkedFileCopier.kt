package ru.hznik.devicebridge.data.file

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.hznik.devicebridge.core.text.toLowerHex

data class ChunkedFileCopyResult(
    val sizeBytes: Long,
    val sha256: String,
)

class AndroidChunkedFileCopier(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {
    init {
        require(bufferSize in 1..MAX_BUFFER_SIZE) { "Copy buffer size is out of range" }
    }

    suspend fun copy(
        input: InputStream,
        output: OutputStream,
        onProgress: suspend (bytesCopied: Long) -> Unit = {},
    ): ChunkedFileCopyResult = withContext(ioDispatcher) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSize)
        var copied = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            copied = Math.addExact(copied, read.toLong())
            onProgress(copied)
        }
        output.flush()
        ChunkedFileCopyResult(
            sizeBytes = copied,
            sha256 = digest.digest().toLowerHex(),
        )
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
        const val MAX_BUFFER_SIZE = 1024 * 1024
    }
}
