package ru.hznik.devicebridge.data.file

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import ru.hznik.devicebridge.domain.file.DEFAULT_FILE_MIME_TYPE
import ru.hznik.devicebridge.domain.file.FileTransferPhase

interface ExternalFileViewerGateway {
    fun canOpen(intent: Intent): Boolean

    fun open(intent: Intent)
}

enum class CompletedFileOpenResult {
    Opened,
    NotCompleted,
    UnsafeUri,
    NoViewer,
}

class AndroidCompletedFileOpener(
    private val viewer: ExternalFileViewerGateway,
) {
    fun open(
        phase: FileTransferPhase,
        contentUri: String,
        mimeType: String,
    ): CompletedFileOpenResult {
        if (phase != FileTransferPhase.COMPLETED) {
            return CompletedFileOpenResult.NotCompleted
        }
        val uri = runCatching { Uri.parse(contentUri) }.getOrNull()
            ?: return CompletedFileOpenResult.UnsafeUri
        if (uri.scheme != ContentResolverScheme) {
            return CompletedFileOpenResult.UnsafeUri
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType.ifBlank { DEFAULT_FILE_MIME_TYPE })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply {
                clipData = ClipData.newRawUri("DeviceBridge file", uri)
            }
        if (!viewer.canOpen(intent)) return CompletedFileOpenResult.NoViewer
        return runCatching {
            viewer.open(intent)
            CompletedFileOpenResult.Opened
        }.getOrDefault(CompletedFileOpenResult.NoViewer)
    }

    private companion object {
        const val ContentResolverScheme = "content"
    }
}

class ContextExternalFileViewerGateway(
    private val context: Context,
) : ExternalFileViewerGateway {
    override fun canOpen(intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null

    override fun open(intent: Intent) {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
