package ru.hznik.devicebridge.data.persistence.room

import ru.hznik.devicebridge.domain.history.HistoryDirection
import ru.hznik.devicebridge.domain.history.HistoryFileMetadata
import ru.hznik.devicebridge.domain.history.HistoryKind
import ru.hznik.devicebridge.domain.history.HistoryOperationId
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryRecordId
import ru.hznik.devicebridge.domain.history.HistoryStatus
import ru.hznik.devicebridge.domain.trust.TrustedBrowser
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId

private const val KIND_TEXT = "text"
private const val KIND_LINK = "link"
private const val KIND_FILE = "file"
private const val DIRECTION_ANDROID_TO_BROWSER = "android_to_browser"
private const val DIRECTION_BROWSER_TO_ANDROID = "browser_to_android"
private const val STATUS_DELIVERED = "delivered"
private const val STATUS_COMPLETED = "completed"
private const val STATUS_CANCELLED = "cancelled"
private const val STATUS_FAILED = "failed"

fun HistoryRecord.toEntity(): HistoryRecordEntity = HistoryRecordEntity(
    recordId = id.value,
    operationId = operationId.value,
    kind = kind.toStorageValue(),
    direction = direction.toStorageValue(),
    browserLabel = browserLabel,
    timestampEpochMillis = timestampEpochMillis,
    terminalStatus = status.toStorageValue(),
    textPreview = textPreview,
    fileDisplayName = file?.displayName,
    fileSizeBytes = file?.sizeBytes,
    fileMimeType = file?.mimeType,
    fileSha256 = file?.sha256,
    failureReason = failureReason,
)

fun HistoryRecordEntity.toDomainOrNull(): HistoryRecord? = runCatching {
    val mappedKind = kind.toHistoryKind()
    val mappedFile = mapFileMetadata(mappedKind)
    HistoryRecord(
        id = HistoryRecordId(recordId),
        operationId = HistoryOperationId(operationId),
        kind = mappedKind,
        direction = direction.toHistoryDirection(),
        browserLabel = browserLabel,
        timestampEpochMillis = timestampEpochMillis,
        status = terminalStatus.toHistoryStatus(),
        textPreview = textPreview,
        file = mappedFile,
        failureReason = failureReason,
    )
}.getOrNull()

fun TrustedBrowserEntity.toDomainOrNull(): TrustedBrowser? = runCatching {
    TrustedBrowser(
        id = TrustedBrowserId(trustedBrowserId),
        browserLabel = browserLabel,
        createdAtEpochMillis = createdAtEpochMillis,
        lastUsedAtEpochMillis = lastUsedAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )
}.getOrNull()

fun TrustedBrowser.toEntity(credentialVerifier: ByteArray): TrustedBrowserEntity =
    TrustedBrowserEntity(
        trustedBrowserId = id.value,
        browserLabel = browserLabel,
        createdAtEpochMillis = createdAtEpochMillis,
        lastUsedAtEpochMillis = lastUsedAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
        credentialVerifier = credentialVerifier.copyOf(),
    )

private fun HistoryRecordEntity.mapFileMetadata(kind: HistoryKind): HistoryFileMetadata? {
    val values = listOf(fileDisplayName, fileSizeBytes, fileMimeType, fileSha256)
    if (kind != HistoryKind.FILE) {
        require(values.all { it == null })
        return null
    }
    require(values.all { it != null })
    return HistoryFileMetadata(
        displayName = requireNotNull(fileDisplayName),
        sizeBytes = requireNotNull(fileSizeBytes),
        mimeType = requireNotNull(fileMimeType),
        sha256 = requireNotNull(fileSha256),
    )
}

private fun HistoryKind.toStorageValue(): String = when (this) {
    HistoryKind.TEXT -> KIND_TEXT
    HistoryKind.LINK -> KIND_LINK
    HistoryKind.FILE -> KIND_FILE
}

private fun String.toHistoryKind(): HistoryKind = when (this) {
    KIND_TEXT -> HistoryKind.TEXT
    KIND_LINK -> HistoryKind.LINK
    KIND_FILE -> HistoryKind.FILE
    else -> error("Unknown history kind")
}

private fun HistoryDirection.toStorageValue(): String = when (this) {
    HistoryDirection.ANDROID_TO_BROWSER -> DIRECTION_ANDROID_TO_BROWSER
    HistoryDirection.BROWSER_TO_ANDROID -> DIRECTION_BROWSER_TO_ANDROID
}

private fun String.toHistoryDirection(): HistoryDirection = when (this) {
    DIRECTION_ANDROID_TO_BROWSER -> HistoryDirection.ANDROID_TO_BROWSER
    DIRECTION_BROWSER_TO_ANDROID -> HistoryDirection.BROWSER_TO_ANDROID
    else -> error("Unknown history direction")
}

private fun HistoryStatus.toStorageValue(): String = when (this) {
    HistoryStatus.DELIVERED -> STATUS_DELIVERED
    HistoryStatus.COMPLETED -> STATUS_COMPLETED
    HistoryStatus.CANCELLED -> STATUS_CANCELLED
    HistoryStatus.FAILED -> STATUS_FAILED
}

private fun String.toHistoryStatus(): HistoryStatus = when (this) {
    STATUS_DELIVERED -> HistoryStatus.DELIVERED
    STATUS_COMPLETED -> HistoryStatus.COMPLETED
    STATUS_CANCELLED -> HistoryStatus.CANCELLED
    STATUS_FAILED -> HistoryStatus.FAILED
    else -> error("Unknown history status")
}
