package ru.hznik.devicebridge.data.file

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContentResolverPartialDocumentProvider(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PartialDocumentProvider {

    override suspend fun listDisplayNames(treeUri: String): Set<String> =
        withContext(ioDispatcher) {
            val tree = Uri.parse(treeUri)
            val documentId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val names = linkedSetOf<String>()
            contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val nameIndex =
                    cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (nameIndex >= 0 && cursor.moveToNext()) {
                    if (!cursor.isNull(nameIndex)) names += cursor.getString(nameIndex)
                }
            } ?: error("Destination provider is unavailable")
            names
        }

    override suspend fun create(
        treeUri: String,
        mimeType: String,
        displayName: String,
    ): String? = withContext(ioDispatcher) {
        runCatching {
            val tree = Uri.parse(treeUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            DocumentsContract.createDocument(
                contentResolver,
                parent,
                mimeType,
                displayName,
            )?.toString()
        }.getOrNull()
    }

    override suspend fun rename(
        documentUri: String,
        displayName: String,
    ): String? = withContext(ioDispatcher) {
        runCatching {
            DocumentsContract.renameDocument(
                contentResolver,
                Uri.parse(documentUri),
                displayName,
            )?.toString()
        }.getOrNull()
    }

    override suspend fun delete(documentUri: String): Boolean =
        withContext(ioDispatcher) {
            runCatching {
                DocumentsContract.deleteDocument(contentResolver, Uri.parse(documentUri))
            }.getOrDefault(false)
        }
}
