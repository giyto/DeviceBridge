package ru.hznik.devicebridge.data.file

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileDraftId
import ru.hznik.devicebridge.domain.file.DraftSourceLease

@Singleton
class FileSourceRegistry @Inject constructor() {
    private val uris = ConcurrentHashMap<FileTransferId, String>()
    private val stagedFiles = ConcurrentHashMap<FileTransferId, File>()
    private val draftUris = ConcurrentHashMap<FileDraftId, String>()
    private val draftStagedFiles = ConcurrentHashMap<FileDraftId, File>()

    @Synchronized
    fun registerDraft(draftId: FileDraftId, uri: String): DraftSourceLease {
        require(uri.startsWith("content://"))
        removeDraftLocked(draftId)
        draftUris[draftId] = uri
        return RegistryDraftSourceLease(this, draftId)
    }

    @Synchronized
    fun registerDraftStaged(draftId: FileDraftId, file: File): DraftSourceLease {
        val canonical = file.canonicalFile
        require(canonical.isFile) { "Staged source must exist" }
        removeDraftLocked(draftId)
        draftStagedFiles[draftId] = canonical
        return RegistryDraftSourceLease(this, draftId)
    }

    fun draftSourceUri(draftId: FileDraftId): String? = draftUris[draftId]

    fun draftStagedFile(draftId: FileDraftId): File? = draftStagedFiles[draftId]

    @Synchronized
    fun register(transferId: FileTransferId, uri: String) {
        require(uri.startsWith("content://"))
        stagedFiles.remove(transferId)?.delete()
        uris[transferId] = uri
    }

    fun sourceUri(transferId: FileTransferId): String? = uris[transferId]

    @Synchronized
    fun registerStaged(transferId: FileTransferId, file: File) {
        val canonical = file.canonicalFile
        require(canonical.isFile) { "Staged source must exist" }
        uris.remove(transferId)
        stagedFiles.put(transferId, canonical)?.delete()
    }

    fun stagedFile(transferId: FileTransferId): File? = stagedFiles[transferId]

    @Synchronized
    internal fun promoteDraft(draftId: FileDraftId, transferId: FileTransferId): Boolean {
        if (uris.containsKey(transferId) || stagedFiles.containsKey(transferId)) return false
        val uri = draftUris.remove(draftId)
        if (uri != null) {
            uris[transferId] = uri
            return true
        }
        val file = draftStagedFiles.remove(draftId)
        if (file != null) {
            stagedFiles[transferId] = file
            return true
        }
        return false
    }

    @Synchronized
    internal fun rollbackDraft(draftId: FileDraftId, transferId: FileTransferId) {
        val uri = uris.remove(transferId)
        if (uri != null) {
            removeDraftLocked(draftId)
            draftUris[draftId] = uri
            return
        }
        val file = stagedFiles.remove(transferId) ?: return
        removeDraftLocked(draftId)
        draftStagedFiles[draftId] = file
    }

    @Synchronized
    internal fun removeDraft(draftId: FileDraftId) {
        removeDraftLocked(draftId)
    }

    fun registeredStagedPaths(): Set<String> =
        (stagedFiles.values + draftStagedFiles.values)
            .mapTo(mutableSetOf()) { it.canonicalPath }

    @Synchronized
    fun remove(transferId: FileTransferId) {
        uris.remove(transferId)
        stagedFiles.remove(transferId)?.delete()
    }

    @Synchronized
    fun clear() {
        uris.clear()
        draftUris.clear()
        val files = stagedFiles.values.toList() + draftStagedFiles.values.toList()
        stagedFiles.clear()
        draftStagedFiles.clear()
        files.forEach(File::delete)
    }

    private fun removeDraftLocked(draftId: FileDraftId) {
        draftUris.remove(draftId)
        draftStagedFiles.remove(draftId)?.delete()
    }
}

private class RegistryDraftSourceLease(
    private val registry: FileSourceRegistry,
    private val draftId: FileDraftId,
) : DraftSourceLease {
    private var promotedTransferId: FileTransferId? = null
    private var closed = false

    @Synchronized
    override fun promote(transferId: FileTransferId): Boolean {
        if (closed || promotedTransferId != null) return false
        return registry.promoteDraft(draftId, transferId).also { promoted ->
            if (promoted) promotedTransferId = transferId
        }
    }

    @Synchronized
    override fun rollback(transferId: FileTransferId) {
        if (closed || promotedTransferId != transferId) return
        registry.rollbackDraft(draftId, transferId)
        promotedTransferId = null
    }

    @Synchronized
    override fun commit(transferId: FileTransferId) {
        if (closed || promotedTransferId != transferId) return
        promotedTransferId = null
        closed = true
    }

    @Synchronized
    override fun release() {
        if (closed) return
        promotedTransferId?.let { registry.rollbackDraft(draftId, it) }
        promotedTransferId = null
        registry.removeDraft(draftId)
        closed = true
    }
}
