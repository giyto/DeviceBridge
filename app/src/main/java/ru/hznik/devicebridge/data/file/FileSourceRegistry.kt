package ru.hznik.devicebridge.data.file

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import ru.hznik.devicebridge.domain.file.FileTransferId

@Singleton
class FileSourceRegistry @Inject constructor() {
    private val uris = ConcurrentHashMap<FileTransferId, String>()
    private val stagedFiles = ConcurrentHashMap<FileTransferId, File>()

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
    fun remove(transferId: FileTransferId) {
        uris.remove(transferId)
        stagedFiles.remove(transferId)?.delete()
    }

    @Synchronized
    fun clear() {
        uris.clear()
        val files = stagedFiles.values.toList()
        stagedFiles.clear()
        files.forEach(File::delete)
    }
}
