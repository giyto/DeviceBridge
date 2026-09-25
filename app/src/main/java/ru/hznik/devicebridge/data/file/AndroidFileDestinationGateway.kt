package ru.hznik.devicebridge.data.file

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.atomic.AtomicBoolean

internal const val DOCUMENT_TREE_READ_WRITE_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

interface DocumentTreePermissionGateway {
    fun acquire(uri: String, grantFlags: Int): Boolean

    fun isAvailable(uri: String): Boolean

    fun release(uri: String, grantFlags: Int)
}

sealed interface DestinationApproval {
    data class Approved(val lease: ScopedDocumentTreeLease) : DestinationApproval
    data object Cancelled : DestinationApproval
    data object Unavailable : DestinationApproval
}

class ScopedDocumentTreeLease internal constructor(
    val uri: String,
    private val grantFlags: Int,
    private val permissions: DocumentTreePermissionGateway,
    private val releasePermissionOnClose: Boolean = true,
) {
    private val released = AtomicBoolean(false)

    fun isAvailable(): Boolean =
        !released.get() && permissions.isAvailable(uri)

    fun release() {
        if (released.compareAndSet(false, true)) {
            if (releasePermissionOnClose) {
                permissions.release(uri, grantFlags)
            }
        }
    }
}

class AndroidFileDestinationGateway(
    private val permissions: DocumentTreePermissionGateway,
) {
    fun approve(
        uri: String?,
        grantFlags: Int,
    ): DestinationApproval {
        if (uri == null) return DestinationApproval.Cancelled
        val scopedFlags = grantFlags and DOCUMENT_TREE_READ_WRITE_FLAGS
        if (scopedFlags == 0 || !permissions.acquire(uri, scopedFlags)) {
            return DestinationApproval.Unavailable
        }
        return DestinationApproval.Approved(
            ScopedDocumentTreeLease(uri, scopedFlags, permissions),
        )
    }

    companion object {
        fun contract(): ActivityResultContracts.OpenDocumentTree =
            ActivityResultContracts.OpenDocumentTree()
    }
}

sealed interface SettingsDestinationApproval {
    data class Approved(val uri: String) : SettingsDestinationApproval
    data object Cancelled : SettingsDestinationApproval
    data object Unavailable : SettingsDestinationApproval
}

class PersistedDestinationPermissionController(
    private val permissions: DocumentTreePermissionGateway,
) {
    fun approve(uri: String?): SettingsDestinationApproval {
        if (uri == null) return SettingsDestinationApproval.Cancelled
        if (!permissions.acquire(uri, DOCUMENT_TREE_READ_WRITE_FLAGS) || !permissions.isAvailable(uri)) {
            return SettingsDestinationApproval.Unavailable
        }
        return SettingsDestinationApproval.Approved(uri)
    }

    fun openPersisted(uri: String): DestinationApproval {
        if (!permissions.isAvailable(uri)) return DestinationApproval.Unavailable
        return DestinationApproval.Approved(
            ScopedDocumentTreeLease(
                uri = uri,
                grantFlags = DOCUMENT_TREE_READ_WRITE_FLAGS,
                permissions = permissions,
                releasePermissionOnClose = false,
            ),
        )
    }
}

class ContentResolverDocumentTreePermissionGateway(
    private val contentResolver: ContentResolver,
) : DocumentTreePermissionGateway {
    override fun acquire(uri: String, grantFlags: Int): Boolean = runCatching {
        contentResolver.takePersistableUriPermission(Uri.parse(uri), grantFlags)
        isAvailable(uri)
    }.getOrDefault(false)

    override fun isAvailable(uri: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        val hasGrant = contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == parsed && permission.isWritePermission
        }
        return persistedTreeIsAvailable(hasGrant) {
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                parsed,
                DocumentsContract.getTreeDocumentId(parsed),
            )
            contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { cursor -> cursor.moveToFirst() } == true
        }
    }

    override fun release(uri: String, grantFlags: Int) {
        runCatching {
            contentResolver.releasePersistableUriPermission(Uri.parse(uri), grantFlags)
        }
    }
}

internal inline fun persistedTreeIsAvailable(
    hasGrant: Boolean,
    providerProbe: () -> Boolean,
): Boolean {
    if (!hasGrant) return false
    return runCatching(providerProbe).getOrDefault(false)
}
