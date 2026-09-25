package ru.hznik.devicebridge.domain.history

private const val MAX_HISTORY_ID_LENGTH = 96
private const val MAX_BROWSER_LABEL_LENGTH = 64
private const val MAX_FAILURE_REASON_CODE_POINTS = 200
private val SHA_256 = Regex("^[a-fA-F0-9]{64}$")

@JvmInline
value class HistoryRecordId(val value: String) {
    init {
        requireOpaqueValue(value, "History record")
    }
}

@JvmInline
value class HistoryOperationId(val value: String) {
    init {
        requireOpaqueValue(value, "History operation")
    }
}

enum class HistoryKind {
    TEXT,
    LINK,
    FILE,
}

enum class HistoryDirection {
    ANDROID_TO_BROWSER,
    BROWSER_TO_ANDROID,
}

enum class HistoryStatus {
    DELIVERED,
    COMPLETED,
    CANCELLED,
    FAILED,
}

data class HistoryFileMetadata(
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val sha256: String,
) {
    init {
        require(displayName.isNotBlank())
        require(displayName.length <= 255)
        require(displayName.none(Char::isISOControl))
        require(sizeBytes >= 0)
        require(mimeType.isNotBlank())
        require(mimeType.length <= 127)
        require(mimeType.none(Char::isISOControl))
        require(SHA_256.matches(sha256))
    }
}

data class HistoryRecord(
    val id: HistoryRecordId,
    val operationId: HistoryOperationId,
    val kind: HistoryKind,
    val direction: HistoryDirection,
    val browserLabel: String,
    val timestampEpochMillis: Long,
    val status: HistoryStatus,
    val textPreview: String?,
    val file: HistoryFileMetadata?,
    val failureReason: String?,
) {
    init {
        require(browserLabel.isNotBlank())
        require(browserLabel.length <= MAX_BROWSER_LABEL_LENGTH)
        require(browserLabel.none(Char::isISOControl))
        require(timestampEpochMillis > 0)
        require(textPreview == null || textPreview.codePointCount() <= MAX_HISTORY_TEXT_PREVIEW_CODE_POINTS)
        require(failureReason == null || failureReason.codePointCount() <= MAX_FAILURE_REASON_CODE_POINTS)
        require(failureReason == null || failureReason.none(Char::isISOControl))

        if (kind == HistoryKind.FILE) {
            require(file != null)
            require(textPreview == null)
            require(status != HistoryStatus.DELIVERED)
        } else {
            require(file == null)
            require(textPreview != null)
            require(status == HistoryStatus.DELIVERED || status == HistoryStatus.FAILED)
        }
    }
}

data class HistoryFilter(
    val directions: Set<HistoryDirection> = emptySet(),
    val kinds: Set<HistoryKind> = emptySet(),
    val statuses: Set<HistoryStatus> = emptySet(),
)

sealed interface HistoryInsertResult {
    data object Inserted : HistoryInsertResult
    data object AlreadyRecorded : HistoryInsertResult
}

private fun requireOpaqueValue(value: String, label: String) {
    require(value.isNotBlank()) { "$label identifier must not be blank" }
    require(value.length <= MAX_HISTORY_ID_LENGTH) { "$label identifier is too long" }
    require(value.none(Char::isISOControl)) { "$label identifier contains control characters" }
}

private fun String.codePointCount(): Int = codePointCount(0, length)
