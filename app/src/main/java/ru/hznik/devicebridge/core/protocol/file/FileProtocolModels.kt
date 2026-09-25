package ru.hznik.devicebridge.core.protocol.file

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.hznik.devicebridge.core.protocol.error.toFailureCode

const val FILE_PROTOCOL_VERSION = 1
const val FILE_OFFER_TYPE = "file.offer"
const val FILE_UPLOAD_READY_TYPE = "file.upload_ready"
const val FILE_PROGRESS_TYPE = "file.progress"
const val FILE_DOWNLOAD_GRANT_TYPE = "file.download_grant"
const val FILE_DOWNLOAD_GRANT_REQUEST_TYPE = "file.download_grant.request"
const val FILE_VERIFY_TYPE = "file.verify"
const val FILE_CANCEL_TYPE = "file.cancel"
const val FILE_SNAPSHOT_TYPE = "file.snapshot"
const val FILE_ERROR_TYPE = "file.error"
const val FILE_UPLOAD_OFFSET_REQUEST_TYPE = "file.upload_offset.request"
const val FILE_UPLOAD_OFFSET_TYPE = "file.upload_offset"

/** Header carrying the byte offset a resumed Browser -> Android upload body starts at. */
const val FILE_UPLOAD_OFFSET_HEADER = "X-DeviceBridge-Upload-Offset"
const val MAX_FILE_BATCH_ITEMS = 32
const val MAX_FILE_SNAPSHOT_ITEMS = 100

private const val MAX_PROTOCOL_ID_LENGTH = 64
private const val MAX_DISPLAY_NAME_LENGTH = 255
private const val MAX_MIME_TYPE_LENGTH = 127
private const val MAX_DOWNLOAD_PATH_LENGTH = 512
private val PROTOCOL_ID = Regex("^[A-Za-z0-9_-]+$")
private val SHA_256 = Regex("^[a-fA-F0-9]{64}$")

@Serializable
enum class FileDirectionDto {
    ANDROID_TO_BROWSER,
    BROWSER_TO_ANDROID,
}

@Serializable
enum class FileTransferStatusDto {
    QUEUED,
    CONNECTING,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

@Serializable
enum class FileProtocolErrorCode {
    INVALID_PAYLOAD,
    UNSUPPORTED_VERSION,
    FILE_TOO_LARGE,
    MESSAGE_CONFLICT,
    SESSION_UNAVAILABLE,
    NOT_APPROVED,
    CHECKSUM_MISMATCH,
    DESTINATION_UNAVAILABLE,
    INSUFFICIENT_SPACE,
    SOURCE_UNAVAILABLE,
    CANCELLED,
    STREAM_FAILED,
}

@Serializable
data class FileMetadataDto(
    val transferId: String,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val sha256: String,
    val direction: FileDirectionDto,
)

@Serializable
data class FileOfferMessage(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val batchId: String,
    val items: List<FileMetadataDto>,
)

@Serializable
data class FileUploadReadyEvent(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val transferId: String,
)

@Serializable
data class FileProgressEvent(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val transferId: String,
    val status: FileTransferStatusDto,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val speedBytesPerSecond: Long,
    /** Same as [FileSnapshotItem.resumableBytes]. */
    val resumableBytes: Long? = null,
)

@Serializable
data class FileDownloadGrantResponse(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val transferId: String,
    val downloadPath: String,
    val expiresAt: Long,
)

@Serializable
data class FileDownloadGrantRequest(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
)

/** Asks the server to prepare the output of an approved upload and report where to resume. */
@Serializable
data class FileUploadOffsetRequest(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
)

@Serializable
data class FileUploadOffsetResponse(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val transferId: String,
    val offsetBytes: Long,
)

@Serializable
data class FileVerificationMessage(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val transferId: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class FileCancellationMessage(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val transferId: String,
)

@Serializable
data class FileSnapshotItem(
    val metadata: FileMetadataDto,
    val status: FileTransferStatusDto,
    val bytesTransferred: Long,
    val speedBytesPerSecond: Long,
    /** Set for a failed item that can continue from these bytes instead of starting over. */
    val resumableBytes: Long? = null,
)

@Serializable
data class FileSnapshotEvent(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val items: List<FileSnapshotItem>,
)

@Serializable
data class FileErrorEvent(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val relatedMessageId: String? = null,
    val transferId: String? = null,
    val code: FileProtocolErrorCode,
    val errorCode: String = code.toFailureCode().wireValue,
)

object FileProtocolJson {
    val format: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    inline fun <reified T> encode(value: T): String = format.encodeToString(value)

    inline fun <reified T> decode(value: String): T = format.decodeFromString(value)
}

enum class FileProtocolValidationError {
    NONE,
    UNSUPPORTED_VERSION,
    INVALID_TYPE,
    INVALID_MESSAGE_ID,
    INVALID_TIMESTAMP,
    INVALID_BATCH,
    INVALID_METADATA,
    INVALID_TRANSFER_ID,
    INVALID_PROGRESS,
    INVALID_DOWNLOAD_PATH,
    INVALID_CHECKSUM,
    INVALID_SNAPSHOT,
    INVALID_RELATED_MESSAGE_ID,
    INVALID_OFFSET,
}

object FileProtocolValidator {
    fun validate(message: FileOfferMessage): FileProtocolValidationError =
        validateEnvelope(message, FILE_OFFER_TYPE)
            .orElse {
                when {
                    !message.batchId.isValidProtocolId() -> FileProtocolValidationError.INVALID_BATCH
                    message.items.size !in 1..MAX_FILE_BATCH_ITEMS -> FileProtocolValidationError.INVALID_BATCH
                    message.items.any { !it.isValid() } -> FileProtocolValidationError.INVALID_METADATA
                    message.items.map(FileMetadataDto::transferId).toSet().size != message.items.size ->
                        FileProtocolValidationError.INVALID_BATCH
                    else -> FileProtocolValidationError.NONE
                }
            }

    fun validate(message: FileUploadReadyEvent): FileProtocolValidationError =
        validateEnvelope(message, FILE_UPLOAD_READY_TYPE)
            .orElse { validateTransferId(message.transferId) }

    fun validate(message: FileProgressEvent): FileProtocolValidationError =
        validateEnvelope(message, FILE_PROGRESS_TYPE)
            .orElse { validateTransferId(message.transferId) }
            .orElse {
                if (
                    message.totalBytes >= 0 &&
                    message.bytesTransferred in 0..message.totalBytes &&
                    message.speedBytesPerSecond >= 0
                ) {
                    FileProtocolValidationError.NONE
                } else {
                    FileProtocolValidationError.INVALID_PROGRESS
                }
            }

    fun validate(message: FileDownloadGrantResponse): FileProtocolValidationError =
        validateEnvelope(message, FILE_DOWNLOAD_GRANT_TYPE)
            .orElse { validateTransferId(message.transferId) }
            .orElse {
                if (
                    message.downloadPath.length in 1..MAX_DOWNLOAD_PATH_LENGTH &&
                    message.downloadPath.startsWith("/api/v1/files/") &&
                    "://" !in message.downloadPath &&
                    message.expiresAt > message.timestamp
                ) {
                    FileProtocolValidationError.NONE
                } else {
                    FileProtocolValidationError.INVALID_DOWNLOAD_PATH
                }
            }

    fun validate(message: FileDownloadGrantRequest): FileProtocolValidationError =
        validateEnvelope(message, FILE_DOWNLOAD_GRANT_REQUEST_TYPE)

    fun validate(message: FileUploadOffsetRequest): FileProtocolValidationError =
        validateEnvelope(message, FILE_UPLOAD_OFFSET_REQUEST_TYPE)

    fun validate(message: FileUploadOffsetResponse): FileProtocolValidationError =
        validateEnvelope(message, FILE_UPLOAD_OFFSET_TYPE)
            .orElse { validateTransferId(message.transferId) }
            .orElse {
                if (message.offsetBytes >= 0) {
                    FileProtocolValidationError.NONE
                } else {
                    FileProtocolValidationError.INVALID_OFFSET
                }
            }

    fun validate(message: FileVerificationMessage): FileProtocolValidationError =
        validateEnvelope(message, FILE_VERIFY_TYPE)
            .orElse { validateTransferId(message.transferId) }
            .orElse {
                if (message.sizeBytes >= 0 && SHA_256.matches(message.sha256)) {
                    FileProtocolValidationError.NONE
                } else {
                    FileProtocolValidationError.INVALID_CHECKSUM
                }
            }

    fun validate(message: FileCancellationMessage): FileProtocolValidationError =
        validateEnvelope(message, FILE_CANCEL_TYPE)
            .orElse { validateTransferId(message.transferId) }

    fun validate(message: FileSnapshotEvent): FileProtocolValidationError =
        validateEnvelope(message, FILE_SNAPSHOT_TYPE)
            .orElse {
                if (
                    message.items.size <= MAX_FILE_SNAPSHOT_ITEMS &&
                    message.items.all { it.isValid() }
                ) {
                    FileProtocolValidationError.NONE
                } else {
                    FileProtocolValidationError.INVALID_SNAPSHOT
                }
            }

    fun validate(message: FileErrorEvent): FileProtocolValidationError =
        validateEnvelope(message, FILE_ERROR_TYPE)
            .orElse {
                if (
                    message.relatedMessageId == null ||
                    message.relatedMessageId.isValidProtocolId()
                ) {
                    FileProtocolValidationError.NONE
                } else {
                    FileProtocolValidationError.INVALID_RELATED_MESSAGE_ID
                }
            }
            .orElse {
                if (message.transferId == null || message.transferId.isValidProtocolId()) {
                    FileProtocolValidationError.NONE
                } else {
                    FileProtocolValidationError.INVALID_TRANSFER_ID
                }
            }

    private fun validateEnvelope(
        message: Any,
        expectedType: String,
    ): FileProtocolValidationError {
        val envelope = when (message) {
            is FileOfferMessage ->
                Envelope(message.protocolVersion, message.messageId, message.type, message.timestamp)
            is FileUploadReadyEvent ->
                Envelope(message.protocolVersion, message.messageId, message.type, message.timestamp)
            is FileProgressEvent ->
                Envelope(message.protocolVersion, message.messageId, message.type, message.timestamp)
            is FileDownloadGrantResponse ->
                Envelope(message.protocolVersion, message.messageId, message.type, message.timestamp)
            is FileDownloadGrantRequest ->
                Envelope(message.protocolVersion, message.messageId, message.type, message.timestamp)
            is FileUploadOffsetRequest ->
                Envelope(message.protocolVersion, message.messageId, message.type, message.timestamp)
            is FileUploadOffsetResponse ->
                Envelope(message.protocolVersion, message.messageId, message.type, message.timestamp)
            is FileVerificationMessage ->
                Envelope(message.protocolVersion, message.messageId, message.type, message.timestamp)
            is FileCancellationMessage ->
                Envelope(message.protocolVersion, message.messageId, message.type, message.timestamp)
            is FileSnapshotEvent ->
                Envelope(message.protocolVersion, message.messageId, message.type, message.timestamp)
            is FileErrorEvent ->
                Envelope(message.protocolVersion, message.messageId, message.type, message.timestamp)
            else -> return FileProtocolValidationError.INVALID_TYPE
        }
        return when {
            envelope.protocolVersion != FILE_PROTOCOL_VERSION ->
                FileProtocolValidationError.UNSUPPORTED_VERSION
            envelope.type != expectedType -> FileProtocolValidationError.INVALID_TYPE
            !envelope.messageId.isValidProtocolId() -> FileProtocolValidationError.INVALID_MESSAGE_ID
            envelope.timestamp <= 0 -> FileProtocolValidationError.INVALID_TIMESTAMP
            else -> FileProtocolValidationError.NONE
        }
    }

    private fun validateTransferId(transferId: String): FileProtocolValidationError =
        if (transferId.isValidProtocolId()) {
            FileProtocolValidationError.NONE
        } else {
            FileProtocolValidationError.INVALID_TRANSFER_ID
        }

    private fun FileMetadataDto.isValid(): Boolean =
        transferId.isValidProtocolId() &&
            displayName.isNotBlank() &&
            displayName.length <= MAX_DISPLAY_NAME_LENGTH &&
            displayName.none(Char::isISOControl) &&
            sizeBytes >= 0 &&
            mimeType.isNotBlank() &&
            mimeType.length <= MAX_MIME_TYPE_LENGTH &&
            mimeType.none(Char::isISOControl) &&
            SHA_256.matches(sha256)

    private fun FileSnapshotItem.isValid(): Boolean =
        metadata.isValid() &&
            bytesTransferred >= 0 &&
            bytesTransferred <= metadata.sizeBytes &&
            speedBytesPerSecond >= 0

    private fun String.isValidProtocolId(): Boolean =
        length in 1..MAX_PROTOCOL_ID_LENGTH && PROTOCOL_ID.matches(this)

    private fun FileProtocolValidationError.orElse(
        next: () -> FileProtocolValidationError,
    ): FileProtocolValidationError =
        if (this == FileProtocolValidationError.NONE) next() else this

    private data class Envelope(
        val protocolVersion: Int,
        val messageId: String,
        val type: String,
        val timestamp: Long,
    )
}
