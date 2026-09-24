package ru.hznik.devicebridge.data.persistence.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "history_records",
    indices = [
        Index(value = ["operation_id", "kind"], unique = true),
        Index(value = ["timestamp_epoch_millis"]),
    ],
)
data class HistoryRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "record_id")
    val recordId: String,
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "direction")
    val direction: String,
    @ColumnInfo(name = "browser_label")
    val browserLabel: String,
    @ColumnInfo(name = "timestamp_epoch_millis")
    val timestampEpochMillis: Long,
    @ColumnInfo(name = "terminal_status")
    val terminalStatus: String,
    @ColumnInfo(name = "text_preview")
    val textPreview: String?,
    @ColumnInfo(name = "file_display_name")
    val fileDisplayName: String?,
    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long?,
    @ColumnInfo(name = "file_mime_type")
    val fileMimeType: String?,
    @ColumnInfo(name = "file_sha256")
    val fileSha256: String?,
    @ColumnInfo(name = "failure_reason")
    val failureReason: String?,
)

@Entity(
    tableName = "trusted_browsers",
    indices = [
        Index(value = ["expires_at_epoch_millis"]),
    ],
)
data class TrustedBrowserEntity(
    @PrimaryKey
    @ColumnInfo(name = "trusted_browser_id")
    val trustedBrowserId: String,
    @ColumnInfo(name = "browser_label")
    val browserLabel: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "last_used_at_epoch_millis")
    val lastUsedAtEpochMillis: Long?,
    @ColumnInfo(name = "expires_at_epoch_millis")
    val expiresAtEpochMillis: Long,
    @ColumnInfo(name = "credential_verifier", typeAffinity = ColumnInfo.BLOB)
    val credentialVerifier: ByteArray,
)

/**
 * A partially received Browser -> Android upload kept in the user's folder so that the same file
 * can continue from [bytesRetained] later. Never part of history and excluded from backup together
 * with the rest of this database.
 */
@Entity(
    tableName = "partial_uploads",
    indices = [
        Index(value = ["sha256", "size_bytes", "display_name", "tree_uri"], unique = true),
        Index(value = ["updated_at_epoch_millis"]),
    ],
)
data class PartialUploadEntity(
    @PrimaryKey
    @ColumnInfo(name = "partial_document_uri")
    val partialDocumentUri: String,
    @ColumnInfo(name = "sha256")
    val sha256: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "tree_uri")
    val treeUri: String,
    @ColumnInfo(name = "bytes_retained")
    val bytesRetained: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)
