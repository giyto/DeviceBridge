package ru.hznik.devicebridge.data.file

import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.FileTransferState

/** Failures after which a Browser -> Android upload keeps the part it has written. */
fun FileTransferFailure?.keepsPartialUpload(): Boolean =
    this == FileTransferFailure.StreamFailed ||
        this == FileTransferFailure.SessionUnavailable ||
        this == FileTransferFailure.InsufficientSpace

/**
 * Bytes a failed transfer may continue from: a kept upload part, or what an interrupted
 * download already delivered to the browser. Null when it can only start over.
 */
fun FileTransferState.resumableBytes(): Long? {
    if (phase != FileTransferPhase.FAILED || bytesTransferred <= 0) return null
    val resumable = when (metadata.direction) {
        FileTransferDirection.BROWSER_TO_ANDROID ->
            metadata.sizeBytes >= RESUMABLE_UPLOAD_MIN_BYTES && failure.keepsPartialUpload()
        FileTransferDirection.ANDROID_TO_BROWSER ->
            failure == FileTransferFailure.StreamFailed
    }
    return bytesTransferred.takeIf { resumable }
}
