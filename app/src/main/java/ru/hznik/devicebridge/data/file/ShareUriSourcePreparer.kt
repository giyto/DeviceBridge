package ru.hznik.devicebridge.data.file

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

interface ShareUriStreamGateway {
    fun openRetained(uri: String): InputStream?

    fun openForStaging(uri: String): InputStream?
}

sealed class PreparedShareSource {
    abstract fun openStream(): InputStream

    abstract fun cleanup()

    class Retained internal constructor(
        private val input: InputStream,
    ) : PreparedShareSource() {
        private val cleaned = AtomicBoolean(false)

        override fun openStream(): InputStream {
            check(!cleaned.get()) { "Retained source is already cleaned" }
            return input
        }

        override fun cleanup() {
            if (cleaned.compareAndSet(false, true)) {
                runCatching { input.close() }
            }
        }
    }

    class Staged internal constructor(
        val file: File,
    ) : PreparedShareSource() {
        private val cleaned = AtomicBoolean(false)

        override fun openStream(): InputStream {
            check(!cleaned.get()) { "Staged source is already cleaned" }
            return file.inputStream()
        }

        override fun cleanup() {
            if (cleaned.compareAndSet(false, true)) {
                file.delete()
            }
        }
    }
}

sealed interface ShareSourcePreparation {
    data class Ready(val source: PreparedShareSource) : ShareSourcePreparation
    data object InsufficientSpace : ShareSourcePreparation
    data object PermissionLost : ShareSourcePreparation
    data object SourceUnavailable : ShareSourcePreparation
    data object SizeChanged : ShareSourcePreparation
}

class ShareUriSourcePreparer(
    private val streamGateway: ShareUriStreamGateway,
    private val noBackupDirectory: File,
    private val usableBytes: () -> Long = { noBackupDirectory.usableSpace },
    private val copier: AndroidChunkedFileCopier = AndroidChunkedFileCopier(),
) {
    init {
        require(noBackupDirectory.exists() || noBackupDirectory.mkdirs()) {
            "No-backup staging directory is unavailable"
        }
        require(noBackupDirectory.isDirectory) { "No-backup staging path must be a directory" }
    }

    suspend fun prepare(
        uri: String,
        expectedSizeBytes: Long,
        preferRetained: Boolean,
    ): ShareSourcePreparation {
        require(expectedSizeBytes >= 0)
        if (expectedSizeBytes > usableBytes()) {
            return ShareSourcePreparation.InsufficientSpace
        }
        return try {
            if (preferRetained) {
                streamGateway.openRetained(uri)?.let { retained ->
                    return ShareSourcePreparation.Ready(
                        PreparedShareSource.Retained(retained),
                    )
                }
            }
            stage(uri, expectedSizeBytes)
        } catch (_: SecurityException) {
            ShareSourcePreparation.PermissionLost
        }
    }

    private suspend fun stage(
        uri: String,
        expectedSizeBytes: Long,
    ): ShareSourcePreparation {
        val input = streamGateway.openForStaging(uri)
            ?: return ShareSourcePreparation.SourceUnavailable
        val stageFile = File.createTempFile(STAGE_PREFIX, STAGE_SUFFIX, noBackupDirectory)
        return try {
            val result = input.use { source ->
                stageFile.outputStream().use { output ->
                    copier.copy(source, output)
                }
            }
            if (result.sizeBytes != expectedSizeBytes) {
                stageFile.delete()
                ShareSourcePreparation.SizeChanged
            } else {
                ShareSourcePreparation.Ready(PreparedShareSource.Staged(stageFile))
            }
        } catch (security: SecurityException) {
            stageFile.delete()
            throw security
        } catch (cancelled: CancellationException) {
            stageFile.delete()
            throw cancelled
        } catch (_: Throwable) {
            stageFile.delete()
            ShareSourcePreparation.SourceUnavailable
        }
    }

    companion object {
        const val STAGE_PREFIX = "share-"
        const val STAGE_SUFFIX = ".devicebridge-stage"

        fun forAndroid(
            context: Context,
            copier: AndroidChunkedFileCopier = AndroidChunkedFileCopier(),
        ): ShareUriSourcePreparer = ShareUriSourcePreparer(
            streamGateway = ContentResolverShareUriStreamGateway(context.contentResolver),
            noBackupDirectory = context.noBackupFilesDir,
            copier = copier,
        )
    }
}

class ContentResolverShareUriStreamGateway(
    private val contentResolver: ContentResolver,
) : ShareUriStreamGateway {
    override fun openRetained(uri: String): InputStream? =
        contentResolver.openInputStream(Uri.parse(uri))

    override fun openForStaging(uri: String): InputStream? =
        contentResolver.openInputStream(Uri.parse(uri))
}
