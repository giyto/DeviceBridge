package ru.hznik.devicebridge.feature.file

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.hznik.devicebridge.data.file.AndroidChunkedFileCopier
import ru.hznik.devicebridge.data.file.AndroidFileSourcePickerGateway
import ru.hznik.devicebridge.data.file.ContentResolverDocumentMetadataSource
import ru.hznik.devicebridge.data.file.FileSourceRegistry
import ru.hznik.devicebridge.domain.file.FileDraftId
import ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES
import ru.hznik.devicebridge.domain.file.effectiveFileLimitBytes

data class FileSelectionPreparation(
    val items: List<FileDraftItem>,
    val rejectedCount: Int,
)

class AndroidFileSelectionPreparer internal constructor(
    private val pickerGateway: AndroidFileSourcePickerGateway,
    private val copier: AndroidChunkedFileCopier,
    private val sourceRegistry: FileSourceRegistry,
    private val stagingDirectory: File,
    private val openInputStream: (String) -> InputStream?,
    private val availableBytes: (File) -> Long,
) {
    init {
        cleanupOrphanStagedFiles()
    }

    constructor(
        context: Context,
        pickerGateway: AndroidFileSourcePickerGateway = AndroidFileSourcePickerGateway(
            ContentResolverDocumentMetadataSource(context.contentResolver),
        ),
        copier: AndroidChunkedFileCopier = AndroidChunkedFileCopier(),
        sourceRegistry: FileSourceRegistry = FileSourceRegistry(),
    ) : this(
        pickerGateway = pickerGateway,
        copier = copier,
        sourceRegistry = sourceRegistry,
        stagingDirectory = File(context.noBackupFilesDir, STAGING_DIRECTORY_NAME),
        openInputStream = { uri -> context.contentResolver.openInputStream(Uri.parse(uri)) },
        availableBytes = File::getUsableSpace,
    )

    suspend fun prepare(
        uris: List<String>,
        stageTemporarySources: Boolean = false,
        effectiveFileLimitBytes: Long = HARD_MAX_FILE_BYTES,
    ): FileSelectionPreparation = withContext(Dispatchers.IO) {
        val maxFileBytes = effectiveFileLimitBytes(effectiveFileLimitBytes)
        val prepared = mutableListOf<FileDraftItem>()
        var rejected = 0
        pickerGateway.inspect(uris).forEach { inspection ->
            val source = inspection.source
            if (source == null || source.sizeBytes > maxFileBytes) {
                rejected += 1
                return@forEach
            }

            // Usable space of a missing directory is reported as 0, so create it before measuring.
            if (
                stageTemporarySources &&
                (!ensureStagingDirectory() || availableBytes(stagingDirectory) < source.sizeBytes)
            ) {
                rejected += 1
                return@forEach
            }

            val input = runCatching {
                openInputStream(source.uri)
            }.getOrNull()
            if (input == null) {
                rejected += 1
                return@forEach
            }
            val draftId = FileDraftId(UUID.randomUUID().toString())
            var stagedFile: File? = null
            val result = runCatching {
                input.use { stream ->
                    if (stageTemporarySources) {
                        File.createTempFile(STAGING_PREFIX, STAGING_SUFFIX, stagingDirectory)
                            .also { stagedFile = it }
                            .outputStream()
                            .use { output -> copier.copy(stream, output) }
                    } else {
                        copier.copy(stream, DiscardOutputStream)
                    }
                }
            }.getOrNull()
            if (result == null || result.sizeBytes != source.sizeBytes) {
                stagedFile?.delete()
                rejected += 1
                return@forEach
            }
            val lease = runCatching {
                if (stagedFile != null) {
                    sourceRegistry.registerDraftStaged(draftId, stagedFile!!)
                } else {
                    sourceRegistry.registerDraft(draftId, source.uri)
                }
            }.getOrNull()
            if (lease == null) {
                stagedFile?.delete()
                rejected += 1
                return@forEach
            }
            prepared += FileDraftItem(
                id = draftId,
                displayName = source.displayName,
                sizeBytes = source.sizeBytes,
                mimeType = source.mimeType,
                sha256 = result.sha256,
                sourceIdentity = source.uri,
                sourceLease = lease,
            )
        }
        FileSelectionPreparation(prepared, rejected)
    }

    private fun ensureStagingDirectory(): Boolean =
        stagingDirectory.isDirectory || stagingDirectory.mkdirs()

    private data object DiscardOutputStream : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    }

    private fun cleanupOrphanStagedFiles() {
        if (!stagingDirectory.exists()) return
        val retainedPaths = sourceRegistry.registeredStagedPaths()
        stagingDirectory.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile &&
                    file.name.startsWith(STAGING_PREFIX) &&
                    file.name.endsWith(STAGING_SUFFIX) &&
                    file.canonicalPath !in retainedPaths
            }
            .forEach(File::delete)
    }

    private companion object {
        const val STAGING_DIRECTORY_NAME = "file-sources"
        const val STAGING_PREFIX = "source-"
        const val STAGING_SUFFIX = ".devicebridge-stage"
    }
}
