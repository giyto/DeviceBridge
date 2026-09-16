package ru.hznik.devicebridge.data.file

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.atomic.AtomicBoolean

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
) {
    private val released = AtomicBoolean(false)

    fun isAvailable(): Boolean =
        !released.get() && permissions.isAvailable(uri)

    fun release() {
        if (released.compareAndSet(false, true)) {
            permissions.release(uri, grantFlags)
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
        val scopedFlags = grantFlags and READ_WRITE_FLAGS
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

        private const val READ_WRITE_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

class ContentResolverDocumentTreePermissionGateway(
    private val contentResolver: ContentResolver,
) : DocumentTreePermissionGateway {
    override fun acquire(uri: String, grantFlags: Int): Boolean = runCatching {
        contentResolver.takePersistableUriPermission(Uri.parse(uri), grantFlags)
        true
    }.getOrDefault(false)

    override fun isAvailable(uri: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        return contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == parsed &&
                (permission.isReadPermission || permission.isWritePermission)
        }
    }

    override fun release(uri: String, grantFlags: Int) {
        runCatching {
            contentResolver.releasePersistableUriPermission(Uri.parse(uri), grantFlags)
        }
    }
}
