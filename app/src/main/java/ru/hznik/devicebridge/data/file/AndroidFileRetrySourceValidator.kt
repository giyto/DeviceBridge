package ru.hznik.devicebridge.data.file

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferState

@Singleton
class AndroidFileRetrySourceValidator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sources: FileSourceRegistry,
) : FileRetrySourceValidator {
    private val ioDispatcher = Dispatchers.IO

    override suspend fun validate(item: FileTransferState): FileRetrySourceValidation {
        if (item.metadata.direction != FileTransferDirection.ANDROID_TO_BROWSER) {
            return FileRetrySourceValidation.VALID
        }
        return withContext(ioDispatcher) {
            try {
                val input = sources.sourceUri(item.metadata.id)?.let { value ->
                    context.contentResolver.openInputStream(Uri.parse(value))
                } ?: sources.stagedFile(item.metadata.id)
                    ?.takeIf { file -> file.isFile }
                    ?.inputStream()
                if (input == null) return@withContext FileRetrySourceValidation.UNAVAILABLE

                input.use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total = Math.addExact(total, read.toLong())
                        if (total > item.metadata.sizeBytes) {
                            return@withContext FileRetrySourceValidation.CHANGED
                        }
                        digest.update(buffer, 0, read)
                    }
                    val hash = digest.digest().joinToString(separator = "") { byte ->
                        "%02x".format(byte)
                    }
                    if (
                        total == item.metadata.sizeBytes &&
                        hash.equals(item.metadata.sha256, ignoreCase = true)
                    ) {
                        FileRetrySourceValidation.VALID
                    } else {
                        FileRetrySourceValidation.CHANGED
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                FileRetrySourceValidation.UNAVAILABLE
            }
        }
    }
}
