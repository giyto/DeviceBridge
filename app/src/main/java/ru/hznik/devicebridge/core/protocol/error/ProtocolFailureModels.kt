package ru.hznik.devicebridge.core.protocol.error

import kotlinx.serialization.Serializable
import ru.hznik.devicebridge.core.protocol.file.FileProtocolErrorCode
import ru.hznik.devicebridge.core.protocol.session.SessionErrorCode
import ru.hznik.devicebridge.core.protocol.text.TextProtocolErrorCode
import ru.hznik.devicebridge.domain.error.FailureCode

@Serializable
data class ProtocolErrorDetails(
    val protocolVersion: Int? = null,
    val operationId: String? = null,
    val direction: String? = null,
    val sizeCategory: String? = null,
    val lifecycleState: String? = null,
)

fun SessionErrorCode.toFailureCode(): FailureCode = when (this) {
    SessionErrorCode.INVALID_PAYLOAD -> FailureCode.INVALID_PAYLOAD
    SessionErrorCode.UNSUPPORTED_VERSION -> FailureCode.PROTOCOL_VERSION_UNSUPPORTED
    SessionErrorCode.INVALID_CODE -> FailureCode.INVALID_PAIRING_CODE
    SessionErrorCode.INVALID_TRUSTED_CREDENTIAL -> FailureCode.INVALID_TRUSTED_CREDENTIAL
    SessionErrorCode.EXPIRED -> FailureCode.PAIRING_REQUEST_EXPIRED
    SessionErrorCode.DENIED -> FailureCode.PAIRING_DENIED
    SessionErrorCode.RATE_LIMITED -> FailureCode.RATE_LIMITED
    SessionErrorCode.CAPACITY_REACHED -> FailureCode.SESSION_CAPACITY_REACHED
    SessionErrorCode.UNAUTHORIZED -> FailureCode.SESSION_UNAUTHORIZED
    SessionErrorCode.SESSION_CLOSED -> FailureCode.SESSION_CLOSED
}

fun TextProtocolErrorCode.toFailureCode(): FailureCode = when (this) {
    TextProtocolErrorCode.INVALID_PAYLOAD -> FailureCode.INVALID_PAYLOAD
    TextProtocolErrorCode.UNSUPPORTED_VERSION -> FailureCode.PROTOCOL_VERSION_UNSUPPORTED
    TextProtocolErrorCode.CONTENT_TOO_LARGE -> FailureCode.CONTENT_TOO_LARGE
    TextProtocolErrorCode.MESSAGE_CONFLICT -> FailureCode.MESSAGE_CONFLICT
    TextProtocolErrorCode.SESSION_UNAVAILABLE -> FailureCode.SESSION_CLOSED
}

fun FileProtocolErrorCode.toFailureCode(): FailureCode = when (this) {
    FileProtocolErrorCode.INVALID_PAYLOAD -> FailureCode.INVALID_PAYLOAD
    FileProtocolErrorCode.UNSUPPORTED_VERSION -> FailureCode.PROTOCOL_VERSION_UNSUPPORTED
    FileProtocolErrorCode.FILE_TOO_LARGE -> FailureCode.FILE_TOO_LARGE
    FileProtocolErrorCode.MESSAGE_CONFLICT -> FailureCode.MESSAGE_CONFLICT
    FileProtocolErrorCode.SESSION_UNAVAILABLE -> FailureCode.SESSION_CLOSED
    FileProtocolErrorCode.NOT_APPROVED -> FailureCode.FILE_NOT_APPROVED
    FileProtocolErrorCode.CHECKSUM_MISMATCH -> FailureCode.FILE_CHECKSUM_MISMATCH
    FileProtocolErrorCode.CANCELLED -> FailureCode.TRANSFER_CANCELLED
    FileProtocolErrorCode.STREAM_FAILED -> FailureCode.FILE_STREAM_FAILED
    FileProtocolErrorCode.DESTINATION_UNAVAILABLE -> FailureCode.FILE_STORAGE_UNAVAILABLE
    FileProtocolErrorCode.INSUFFICIENT_SPACE -> FailureCode.FILE_INSUFFICIENT_SPACE
    FileProtocolErrorCode.SOURCE_UNAVAILABLE -> FailureCode.FILE_SOURCE_UNAVAILABLE
}
