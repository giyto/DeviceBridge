package ru.hznik.devicebridge.data.file

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
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
    private val partialUploads: PartialUploadStore,
) : FileUploadTargetFactory {
    private val manager = PartialDocumentManager(
        ContentResolverPartialDocumentProvider(context.contentResolver),
    )
    private val preparer = ResumableUploadPreparer(
        manager = manager,
        store = partialUploads,
        io = ContentResolverDocumentIo(context.contentResolver),
    )

    override suspend fun create(
        destinationId: FileDestinationId,
        metadata: FileTransferMetadata,
    ): FileUploadTarget = create(destinationId, metadata, resume = false)

    override suspend fun create(
        destinationId: FileDestinationId,
        metadata: FileTransferMetadata,
        resume: Boolean,
    ): FileUploadTarget {
        try {
            val treeUri = destinationLeases.uri(metadata.id, destinationId)
                ?: error("Destination lease is unavailable")
            return AndroidUploadTarget(
                transferId = metadata.id,
                mimeType = metadata.mimeType,
                prepared = preparer.prepare(treeUri, metadata, resume),
                manager = manager,
                partialUploads = partialUploads,
                completedFiles = completedFiles,
                destinationLeases = destinationLeases,
            )
        } catch (throwable: Throwable) {
            destinationLeases.release(metadata.id)
            throw throwable
        }
    }
}

private class AndroidUploadTarget(
    private val transferId: FileTransferId,
    private val mimeType: String,
    private val prepared: PreparedUpload,
    private val manager: PartialDocumentManager,
    private val partialUploads: PartialUploadStore,
    private val completedFiles: CompletedFileRegistry,
    private val destinationLeases: FileDestinationLeaseRegistry,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : FileUploadTarget {
    private val closed = AtomicBoolean(false)
    private val committed = AtomicBoolean(false)
    private val deleted = AtomicBoolean(false)
    private val retained = AtomicBoolean(false)
    private val output = CountingOutputStream(prepared.output)

    override val offsetBytes: Long = prepared.offsetBytes

    override fun outputStream(): OutputStream = output

    override fun digest(): MessageDigest = prepared.digest

    override suspend fun commit() {
        if (deleted.get() || !committed.compareAndSet(false, true)) return
        try {
            close()
            when (val result = manager.finalize(prepared.handle)) {
                is PartialDocumentFinalization.Completed -> {
                    completedFiles.register(transferId, result.uri)
                    partialUploads.forget(prepared.handle.documentUri)
                }
                is PartialDocumentFinalization.PartialRemains -> {
                    manager.cleanup(prepared.handle)
                    partialUploads.forget(prepared.handle.documentUri)
                    error("Destination provider could not finalize the partial document")
                }
            }
        } finally {
            destinationLeases.release(transferId)
        }
    }

    /** Deletes the output; this also wins over an earlier [retain], e.g. after a user cancel. */
    override suspend fun abort() {
        if (committed.get() || !deleted.compareAndSet(false, true)) return
        try {
            close()
            manager.cleanup(prepared.handle)
            partialUploads.forget(prepared.handle.documentUri)
        } finally {
            destinationLeases.release(transferId)
        }
    }

    override suspend fun retain() {
        val key = prepared.resumeKey
        val stored = prepared.offsetBytes + output.written
        if (key == null || stored <= 0) {
            abort()
            return
        }
        if (committed.get() || deleted.get() || !retained.compareAndSet(false, true)) return
        try {
            close()
            partialUploads.save(
                PartialUploadRecord(
                    key = key,
                    documentUri = prepared.handle.documentUri,
                    mimeType = mimeType,
                    bytesRetained = stored,
                    updatedAtEpochMillis = nowEpochMillis(),
                ),
            )
        } finally {
            destinationLeases.release(transferId)
        }
    }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) output.close()
    }
}

private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    @Volatile
    var written: Long = 0
        private set

    override fun write(b: Int) {
        delegate.write(b)
        written += 1
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        written += len
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
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
