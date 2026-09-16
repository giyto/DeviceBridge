package ru.hznik.devicebridge.data.file

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata

@Singleton
class CompletedFileRegistry @Inject constructor() {
    private val uris = ConcurrentHashMap<FileTransferId, String>()
    fun register(id: FileTransferId, uri: String) { uris[id] = uri }
    fun uri(id: FileTransferId): String? = uris[id]
    fun clear() = uris.clear()
}

@Singleton
class AndroidFileUploadTargetFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val completedFiles: CompletedFileRegistry,
    private val destinationLeases: FileDestinationLeaseRegistry,
) : FileUploadTargetFactory {
    private val manager = PartialDocumentManager(
        ContentResolverPartialDocumentProvider(context.contentResolver),
    )

    override suspend fun create(
        destinationId: FileDestinationId,
        metadata: FileTransferMetadata,
    ): FileUploadTarget {
        try {
            val handle = manager.create(
                treeUri = destinationId.value,
                requestedName = metadata.displayName,
                fallbackId = metadata.id.value,
                mimeType = metadata.mimeType,
            )
            val output = context.contentResolver.openOutputStream(Uri.parse(handle.documentUri), "w")
                ?: run {
                    manager.cleanup(handle)
                    error("Destination provider did not open output")
                }
            return AndroidUploadTarget(
                metadata.id,
                handle,
                output,
                manager,
                completedFiles,
                destinationLeases,
            )
        } catch (throwable: Throwable) {
            destinationLeases.release(metadata.id)
            throw throwable
        }
    }
}

private class AndroidUploadTarget(
    private val transferId: FileTransferId,
    private val handle: PartialDocumentHandle,
    private val output: OutputStream,
    private val manager: PartialDocumentManager,
    private val completedFiles: CompletedFileRegistry,
    private val destinationLeases: FileDestinationLeaseRegistry,
) : FileUploadTarget {
    private val closed = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)

    override fun outputStream(): OutputStream = output

    override suspend fun commit() {
        if (!terminal.compareAndSet(false, true)) return
        try {
            close()
            when (val result = manager.finalize(handle)) {
                is PartialDocumentFinalization.Completed -> completedFiles.register(transferId, result.uri)
                is PartialDocumentFinalization.PartialRemains -> {
                    manager.cleanup(handle)
                    error("Destination provider could not finalize the partial document")
                }
            }
        } finally {
            destinationLeases.release(transferId)
        }
    }

    override suspend fun abort() {
        if (!terminal.compareAndSet(false, true)) return
        try {
            close()
            manager.cleanup(handle)
        } finally {
            destinationLeases.release(transferId)
        }
    }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) output.close()
    }
}

@Singleton
class AndroidFileDownloadSourceFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sources: FileSourceRegistry,
) : FileDownloadSourceFactory {
    override suspend fun create(metadata: FileTransferMetadata): FileDownloadSource {
        val input = sources.sourceUri(metadata.id)?.let { uri ->
            context.contentResolver.openInputStream(Uri.parse(uri))
        } ?: sources.stagedFile(metadata.id)?.inputStream()
        ?: error("File source could not be opened")
        return AndroidDownloadSource(input)
    }
}

private class AndroidDownloadSource(
    private val input: InputStream,
) : FileDownloadSource {
    private val closed = AtomicBoolean(false)
    override fun inputStream(): InputStream = input
    override suspend fun close() {
        if (closed.compareAndSet(false, true)) input.close()
    }
}
