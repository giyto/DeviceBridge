package ru.hznik.devicebridge.domain.file

import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId

private const val MAX_FILE_TRANSFER_ID_LENGTH = 64
private const val MAX_FILE_DISPLAY_NAME_LENGTH = 255
private const val MAX_FILE_MIME_TYPE_LENGTH = 127
private val FILE_SHA_256 = Regex("^[a-fA-F0-9]{64}$")
private val FILE_TRANSFER_ID = Regex("^[A-Za-z0-9_-]+$")

@JvmInline
value class FileTransferId(val value: String) {
    init {
        require(value.isNotBlank()) { "Transfer identifier must not be blank" }
        require(value.length <= MAX_FILE_TRANSFER_ID_LENGTH) { "Transfer identifier is too long" }
        require(value.none(Char::isISOControl)) { "Transfer identifier contains control characters" }
        require(FILE_TRANSFER_ID.matches(value)) { "Transfer identifier contains unsupported characters" }
    }
}

@JvmInline
value class FileDraftId(val value: String) {
    init {
        require(value.isNotBlank()) { "Draft identifier must not be blank" }
        require(value.length <= MAX_FILE_TRANSFER_ID_LENGTH) { "Draft identifier is too long" }
        require(value.none(Char::isISOControl)) { "Draft identifier contains control characters" }
        require(FILE_TRANSFER_ID.matches(value)) { "Draft identifier contains unsupported characters" }
    }
}

interface DraftSourceLease {
    fun promote(transferId: FileTransferId): Boolean
    fun rollback(transferId: FileTransferId)
    fun commit(transferId: FileTransferId)
    fun release()
}

@JvmInline
value class FileDestinationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Destination identifier must not be blank" }
        require(value.length <= 64) { "Destination identifier is too long" }
        require(value.none(Char::isISOControl)) { "Destination identifier contains control characters" }
    }
}

@JvmInline
value class FileCommandId(val value: String) {
    init {
        require(value.isNotBlank()) { "Command identifier must not be blank" }
        require(value.length <= 64) { "Command identifier is too long" }
        require(FILE_TRANSFER_ID.matches(value)) { "Command identifier contains unsupported characters" }
    }
}

enum class FileTransferDirection {
    ANDROID_TO_BROWSER,
    BROWSER_TO_ANDROID,
}

enum class FileTransferPhase {
    QUEUED,
    CONNECTING,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    CANCELLED,
    FAILED,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == CANCELLED || this == FAILED
}

sealed interface FileTransferFailure {
    data object ChecksumMismatch : FileTransferFailure
    data object StreamFailed : FileTransferFailure
    data object SessionUnavailable : FileTransferFailure
    data object StorageUnavailable : FileTransferFailure
    data object CapacityReached : FileTransferFailure
}

data class FileTransferMetadata(
    val id: FileTransferId,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val sha256: String,
    val direction: FileTransferDirection,
) {
    init {
        require(displayName.isNotBlank()) { "Display name must not be blank" }
        require(displayName.length <= MAX_FILE_DISPLAY_NAME_LENGTH) { "Display name is too long" }
        require(displayName.none(Char::isISOControl)) { "Display name contains control characters" }
        require(sizeBytes >= 0) { "File size must not be negative" }
        require(mimeType.isNotBlank()) { "MIME type must not be blank" }
        require(mimeType.length <= MAX_FILE_MIME_TYPE_LENGTH) { "MIME type is too long" }
        require(mimeType.none(Char::isISOControl)) { "MIME type contains control characters" }
        require(FILE_SHA_256.matches(sha256)) { "SHA-256 must contain 64 hexadecimal characters" }
    }
}

data class FileTransferSnapshot(
    val items: List<FileTransferState>,
) {
    init {
        require(items.map { it.metadata.id }.distinct().size == items.size)
    }

    fun item(id: FileTransferId): FileTransferState? =
        items.firstOrNull { it.metadata.id == id }
}

data class CreateFileTransfersRequest(
    val commandId: FileCommandId,
    val generationId: ServerGenerationId,
    val ownerSessionId: BrowserSessionId,
    val files: List<FileTransferMetadata>,
    val batchId: String = "",
) {
    init {
        require(files.size in 1..MAX_FILE_BATCH_SIZE)
        require(files.map(FileTransferMetadata::id).distinct().size == files.size)
    }
}

data class VerifyFileTransferRequest(
    val transferId: FileTransferId,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(sizeBytes in 0..HARD_MAX_FILE_BYTES)
        require(FILE_SHA_256.matches(sha256))
    }
}

sealed interface FileTransferOperationResult {
    data object Accepted : FileTransferOperationResult
    data object NotFound : FileTransferOperationResult
    data object InvalidState : FileTransferOperationResult
    data object Conflict : FileTransferOperationResult
    data class Rejected(val failure: FileTransferFailure) : FileTransferOperationResult
}

@ConsistentCopyVisibility
data class FileTransferState private constructor(
    val generationId: ServerGenerationId,
    val ownerSessionId: BrowserSessionId,
    val metadata: FileTransferMetadata,
    val phase: FileTransferPhase,
    val bytesTransferred: Long,
    val speedBytesPerSecond: Long,
    val failure: FileTransferFailure?,
) {
    init {
        require(bytesTransferred in 0..metadata.sizeBytes)
        require(speedBytesPerSecond >= 0)
        require((phase == FileTransferPhase.FAILED) == (failure != null))
        if (phase == FileTransferPhase.COMPLETED) {
            require(bytesTransferred == metadata.sizeBytes)
        }
    }

    companion object {
        fun queued(
            generationId: ServerGenerationId,
            ownerSessionId: BrowserSessionId,
            metadata: FileTransferMetadata,
        ): FileTransferState = FileTransferState(
            generationId = generationId,
            ownerSessionId = ownerSessionId,
            metadata = metadata,
            phase = FileTransferPhase.QUEUED,
            bytesTransferred = 0,
            speedBytesPerSecond = 0,
            failure = null,
        )
    }

    internal fun evolve(
        phase: FileTransferPhase = this.phase,
        bytesTransferred: Long = this.bytesTransferred,
        speedBytesPerSecond: Long = this.speedBytesPerSecond,
        failure: FileTransferFailure? = this.failure,
    ): FileTransferState = FileTransferState(
        generationId = generationId,
        ownerSessionId = ownerSessionId,
        metadata = metadata,
        phase = phase,
        bytesTransferred = bytesTransferred,
        speedBytesPerSecond = speedBytesPerSecond,
        failure = failure,
    )
}
