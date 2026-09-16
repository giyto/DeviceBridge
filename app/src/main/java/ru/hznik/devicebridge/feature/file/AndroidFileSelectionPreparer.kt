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
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES

data class FileSelectionPreparation(
    val items: List<FileSelectionItem>,
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
    ): FileSelectionPreparation = withContext(Dispatchers.IO) {
        val prepared = mutableListOf<FileSelectionItem>()
        var rejected = 0
        pickerGateway.inspect(uris).forEach { inspection ->
            val source = inspection.source
            if (source == null || source.sizeBytes > HARD_MAX_FILE_BYTES) {
                rejected += 1
                return@forEach
            }

            if (stageTemporarySources && availableBytes(stagingDirectory) < source.sizeBytes) {
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
            val transferId = FileTransferId(UUID.randomUUID().toString())
            var stagedFile: File? = null
            val result = runCatching {
                input.use { stream ->
                    if (stageTemporarySources) {
                        check(stagingDirectory.exists() || stagingDirectory.mkdirs()) {
                            "Unable to create private staging directory"
                        }
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
            val registered = runCatching {
                if (stagedFile != null) {
                    sourceRegistry.registerStaged(transferId, stagedFile!!)
                } else {
                    sourceRegistry.register(transferId, source.uri)
                }
            }.isSuccess
            if (!registered) {
                stagedFile?.delete()
                rejected += 1
                return@forEach
            }
            prepared += FileSelectionItem(
                transferId = transferId,
                uri = source.uri,
                displayName = source.displayName,
                sizeBytes = source.sizeBytes,
                mimeType = source.mimeType,
                sha256 = result.sha256,
            )
        }
        FileSelectionPreparation(prepared, rejected)
    }

    private data object DiscardOutputStream : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    }

    private companion object {
        const val STAGING_DIRECTORY_NAME = "file-sources"
        const val STAGING_PREFIX = "source-"
        const val STAGING_SUFFIX = ".devicebridge-stage"
    }
}
