package ru.hznik.devicebridge.data.file

import java.util.Locale
import ru.hznik.devicebridge.domain.file.DEFAULT_FILE_MIME_TYPE
import ru.hznik.devicebridge.domain.file.MAX_SAFE_FILENAME_LENGTH
import ru.hznik.devicebridge.domain.file.SafeFilenameResolver

interface PartialDocumentProvider {
    suspend fun listDisplayNames(treeUri: String): Set<String>

    suspend fun create(
        treeUri: String,
        mimeType: String,
        displayName: String,
    ): String?

    suspend fun rename(documentUri: String, displayName: String): String?

    suspend fun delete(documentUri: String): Boolean

    /** Current size of the document, or null when it no longer exists or is unreadable. */
    suspend fun size(documentUri: String): Long?
}

data class PartialDocumentHandle(
    val documentUri: String,
    val partialDisplayName: String,
    val finalDisplayName: String,
)

sealed interface PartialDocumentFinalization {
    data class Completed(val uri: String) : PartialDocumentFinalization

    data class PartialRemains(
        val uri: String,
        val displayName: String,
    ) : PartialDocumentFinalization
}

sealed interface PartialDocumentCleanup {
    data object Deleted : PartialDocumentCleanup

    data class PartialRemains(
        val uri: String,
        val displayName: String,
    ) : PartialDocumentCleanup
}

class PartialDocumentManager(
    private val provider: PartialDocumentProvider,
) {
    suspend fun create(
        treeUri: String,
        requestedName: String,
        fallbackId: String,
    ): PartialDocumentHandle {
        val existingNames = provider.listDisplayNames(treeUri)
        val normalized = SafeFilenameResolver.normalize(requestedName, fallbackId)
        val finalName = SafeFilenameResolver.resolveCollision(normalized, existingNames)
        val partialName = resolvePartialName(finalName, existingNames)
        // A neutral type: providers append an extension for known types ("….partial.mp4"),
        // and media indexers would then show an unfinished file as a video or photo.
        val documentUri = provider.create(treeUri, DEFAULT_FILE_MIME_TYPE, partialName)
            ?: error("Destination provider did not create a partial document")
        return PartialDocumentHandle(
            documentUri = documentUri,
            partialDisplayName = partialName,
            finalDisplayName = finalName,
        )
    }

    /**
     * Takes over an existing partial document for a resumed upload. The final name is resolved
     * again because the folder may have changed since the partial was created.
     */
    suspend fun adopt(
        treeUri: String,
        documentUri: String,
        requestedName: String,
        fallbackId: String,
    ): PartialDocumentHandle {
        val existingNames = provider.listDisplayNames(treeUri)
        val normalized = SafeFilenameResolver.normalize(requestedName, fallbackId)
        val finalName = SafeFilenameResolver.resolveCollision(normalized, existingNames)
        return PartialDocumentHandle(
            documentUri = documentUri,
            partialDisplayName = normalized + PARTIAL_SUFFIX,
            finalDisplayName = finalName,
        )
    }

    suspend fun finalize(
        handle: PartialDocumentHandle,
    ): PartialDocumentFinalization {
        val renamed = provider.rename(handle.documentUri, handle.finalDisplayName)
        return if (renamed != null) {
            PartialDocumentFinalization.Completed(renamed)
        } else {
            PartialDocumentFinalization.PartialRemains(
                uri = handle.documentUri,
                displayName = handle.partialDisplayName,
            )
        }
    }

    suspend fun cleanup(
        handle: PartialDocumentHandle,
    ): PartialDocumentCleanup =
        if (provider.delete(handle.documentUri)) {
            PartialDocumentCleanup.Deleted
        } else {
            PartialDocumentCleanup.PartialRemains(
                uri = handle.documentUri,
                displayName = handle.partialDisplayName,
            )
        }

    private fun resolvePartialName(
        finalName: String,
        existingNames: Set<String>,
    ): String {
        val existing = existingNames.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        var index = 0
        while (true) {
            val counter = if (index == 0) "" else "." + index
            val suffix = counter + PARTIAL_SUFFIX
            val base = finalName
                .take((MAX_SAFE_FILENAME_LENGTH - suffix.length).coerceAtLeast(1))
                .trimEnd(' ', '.')
            val candidate = base + suffix
            if (candidate.lowercase(Locale.ROOT) !in existing) return candidate
            index += 1
        }
    }

    private companion object {
        const val PARTIAL_SUFFIX = ".devicebridge-partial"
    }
}
