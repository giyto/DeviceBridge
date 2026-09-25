package ru.hznik.devicebridge.domain.file

const val HARD_MAX_FILE_BYTES = 1_073_741_824L
const val MAX_FILE_BATCH_SIZE = 32
const val DEFAULT_FILE_MIME_TYPE = "application/octet-stream"

fun effectiveFileLimitBytes(requestedBytes: Long): Long =
    requestedBytes.coerceIn(1L, HARD_MAX_FILE_BYTES)

private val VALID_TRANSFER_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

data class FileMetadataCandidate(
    val transferId: String,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val sha256: String,
    val direction: FileTransferDirection,
)

enum class FileMetadataError {
    INVALID_TRANSFER_ID,
    INVALID_DISPLAY_NAME,
    INVALID_SIZE,
    FILE_TOO_LARGE,
    INVALID_CHECKSUM,
    SIZE_MISMATCH,
}

sealed interface FileMetadataValidation {
    data class Valid(val metadata: FileTransferMetadata) : FileMetadataValidation
    data class Invalid(val error: FileMetadataError) : FileMetadataValidation
}

sealed interface FileBatchValidation {
    data class Valid(val items: List<FileTransferMetadata>) : FileBatchValidation
    data object Empty : FileBatchValidation
    data object TooMany : FileBatchValidation
    data object DuplicateTransferId : FileBatchValidation
    data class InvalidItem(
        val index: Int,
        val error: FileMetadataError,
    ) : FileBatchValidation
}

object FileMetadataValidator {

    fun validate(
        candidate: FileMetadataCandidate,
        maxFileBytes: Long = HARD_MAX_FILE_BYTES,
    ): FileMetadataValidation {
        val effectiveLimit = effectiveFileLimitBytes(maxFileBytes)
        if (!VALID_TRANSFER_ID.matches(candidate.transferId)) {
            return FileMetadataValidation.Invalid(FileMetadataError.INVALID_TRANSFER_ID)
        }
        if (
            candidate.displayName.isBlank() ||
            candidate.displayName.length > MAX_FILE_DISPLAY_NAME_LENGTH ||
            candidate.displayName.any(Char::isISOControl)
        ) {
            return FileMetadataValidation.Invalid(FileMetadataError.INVALID_DISPLAY_NAME)
        }
        if (candidate.sizeBytes < 0) {
            return FileMetadataValidation.Invalid(FileMetadataError.INVALID_SIZE)
        }
        if (candidate.sizeBytes > effectiveLimit) {
            return FileMetadataValidation.Invalid(FileMetadataError.FILE_TOO_LARGE)
        }
        if (!FILE_SHA_256.matches(candidate.sha256)) {
            return FileMetadataValidation.Invalid(FileMetadataError.INVALID_CHECKSUM)
        }

        return FileMetadataValidation.Valid(
            FileTransferMetadata(
                id = FileTransferId(candidate.transferId),
                displayName = candidate.displayName,
                sizeBytes = candidate.sizeBytes,
                mimeType = candidate.mimeType.toSafeMimeType(),
                sha256 = candidate.sha256.lowercase(),
                direction = candidate.direction,
            ),
        )
    }

    fun validateBatch(
        candidates: List<FileMetadataCandidate>,
        maxFileBytes: Long = HARD_MAX_FILE_BYTES,
    ): FileBatchValidation {
        val effectiveLimit = effectiveFileLimitBytes(maxFileBytes)
        if (candidates.isEmpty()) return FileBatchValidation.Empty
        if (candidates.size > MAX_FILE_BATCH_SIZE) return FileBatchValidation.TooMany
        if (candidates.map(FileMetadataCandidate::transferId).toSet().size != candidates.size) {
            return FileBatchValidation.DuplicateTransferId
        }
        val validated = ArrayList<FileTransferMetadata>(candidates.size)
        candidates.forEachIndexed { index, candidate ->
            when (val result = validate(candidate, effectiveLimit)) {
                is FileMetadataValidation.Valid -> validated += result.metadata
                is FileMetadataValidation.Invalid ->
                    return FileBatchValidation.InvalidItem(index, result.error)
            }
        }
        return FileBatchValidation.Valid(validated)
    }

    private fun String?.toSafeMimeType(): String =
        this?.takeIf {
            it.isNotBlank() &&
                it.length <= MAX_FILE_MIME_TYPE_LENGTH &&
                it.none(Char::isISOControl)
        } ?: DEFAULT_FILE_MIME_TYPE
}
