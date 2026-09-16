package ru.hznik.devicebridge.data.file

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts

data class AndroidDocumentMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
    val mimeType: String?,
)

fun interface AndroidDocumentMetadataSource {
    fun read(uri: String): AndroidDocumentMetadata?
}

data class AndroidFileSource(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
)

enum class AndroidFileSourceError {
    UNAVAILABLE_PROVIDER,
}

data class AndroidFileSourceInspection(
    val uri: String,
    val source: AndroidFileSource?,
    val error: AndroidFileSourceError?,
)

class AndroidFileSourcePickerGateway(
    private val metadataSource: AndroidDocumentMetadataSource,
) {
    fun inspect(uris: List<String>): List<AndroidFileSourceInspection> =
        uris.distinct().map { uri ->
            val metadata = metadataSource.read(uri)
            val size = metadata?.sizeBytes
            if (metadata == null || size == null || size < 0) {
                AndroidFileSourceInspection(
                    uri = uri,
                    source = null,
                    error = AndroidFileSourceError.UNAVAILABLE_PROVIDER,
                )
            } else {
                AndroidFileSourceInspection(
                    uri = uri,
                    source = AndroidFileSource(
                        uri = uri,
                        displayName = metadata.displayName
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?: DEFAULT_DISPLAY_NAME,
                        sizeBytes = size,
                        mimeType = metadata.mimeType
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?: DEFAULT_MIME_TYPE,
                    ),
                    error = null,
                )
            }
        }

    companion object {
        const val DEFAULT_DISPLAY_NAME = "document"
        const val DEFAULT_MIME_TYPE = "application/octet-stream"

        fun contract(): ActivityResultContracts.OpenMultipleDocuments =
            ActivityResultContracts.OpenMultipleDocuments()
    }
}

class ContentResolverDocumentMetadataSource(
    private val contentResolver: ContentResolver,
) : AndroidDocumentMetadataSource {
    override fun read(uri: String): AndroidDocumentMetadata? = runCatching {
        val parsedUri = Uri.parse(uri)
        var displayName: String? = null
        var sizeBytes: Long? = null
        contentResolver.query(
            parsedUri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }
        if (sizeBytes == null) {
            sizeBytes = contentResolver
                .openAssetFileDescriptor(parsedUri, "r")
                ?.use { descriptor -> descriptor.length.takeIf { length -> length >= 0 } }
        }
        if (sizeBytes == null) return@runCatching null
        AndroidDocumentMetadata(
            displayName = displayName ?: parsedUri.lastPathSegment,
            sizeBytes = sizeBytes,
            mimeType = contentResolver.getType(parsedUri),
        )
    }.getOrNull()
}
