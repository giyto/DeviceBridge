package ru.hznik.devicebridge.domain.error

enum class FailureSeverity {
    RECOVERABLE,
    TERMINAL,
}

enum class RecoveryAction {
    REQUEST_PERMISSION,
    OPEN_SETTINGS,
    CONNECT_TO_LOCAL_NETWORK,
    START_SERVER,
    RETRY,
    MANAGE_SESSIONS,
    PAIR_AGAIN,
    SELECT_SESSION,
    UPDATE_CLIENT,
    START_NEW_OPERATION,
    EDIT_CONTENT,
    SELECT_FILE,
    SELECT_DESTINATION,
    REDUCE_SELECTION,
    EDIT_SETTING,
    RESET_CERTIFICATE,
}

enum class FailureCode(val wireValue: String) {
    UNKNOWN_ERROR("unknown_error"),
    LOCAL_NETWORK_PERMISSION_DENIED("local_network_permission_denied"),
    LOCAL_NETWORK_PERMISSION_REVOKED("local_network_permission_revoked"),
    NO_LAN_NETWORK("no_lan_network"),
    AMBIGUOUS_LAN_NETWORK("ambiguous_lan_network"),
    NETWORK_LOST("network_lost"),
    ADDRESS_CHANGED("address_changed"),
    FOREGROUND_START_NOT_ALLOWED("foreground_start_not_allowed"),
    SERVER_START_FAILED("server_start_failed"),
    SERVER_STOP_TIMEOUT("server_stop_timeout"),
    SECURE_CERTIFICATE_UNAVAILABLE("secure_certificate_unavailable"),
    SESSION_CAPACITY_REACHED("session_capacity_reached"),
    SERVER_GENERATION_CLOSED("server_generation_closed"),
    PAIRING_REQUEST_EXPIRED("pairing_request_expired"),
    PAIRING_REQUEST_NOT_FOUND("pairing_request_not_found"),
    TEXT_CONNECTION_LOST("text_connection_lost"),
    SESSION_CLOSED("session_closed"),
    PROTOCOL_VERSION_UNSUPPORTED("protocol_version_unsupported"),
    EMPTY_CONTENT("empty_content"),
    CONTENT_TOO_LARGE("content_too_large"),
    MESSAGE_CONFLICT("message_conflict"),
    MESSAGE_NOT_FOUND("message_not_found"),
    FILE_CHECKSUM_MISMATCH("file_checksum_mismatch"),
    FILE_STREAM_FAILED("file_stream_failed"),
    FILE_STORAGE_UNAVAILABLE("file_storage_unavailable"),
    FILE_INSUFFICIENT_SPACE("file_insufficient_space"),
    FILE_CAPACITY_REACHED("file_capacity_reached"),
    FILE_SOURCE_UNAVAILABLE("file_source_unavailable"),
    INVALID_TRANSFER_ID("invalid_transfer_id"),
    INVALID_FILE_NAME("invalid_file_name"),
    INVALID_FILE_SIZE("invalid_file_size"),
    FILE_TOO_LARGE("file_too_large"),
    INVALID_CHECKSUM("invalid_checksum"),
    FILE_SIZE_MISMATCH("file_size_mismatch"),
    HISTORY_WRITE_FAILED("history_write_failed"),
    INVALID_DEVICE_NAME("invalid_device_name"),
    INVALID_RETENTION_DAYS("invalid_retention_days"),
    INVALID_FILE_LIMIT("invalid_file_limit"),
    INVALID_PAYLOAD("invalid_payload"),
    INVALID_PAIRING_CODE("invalid_pairing_code"),
    INVALID_TRUSTED_CREDENTIAL("invalid_trusted_credential"),
    PAIRING_DENIED("pairing_denied"),
    RATE_LIMITED("rate_limited"),
    SESSION_UNAUTHORIZED("session_unauthorized"),
    FILE_NOT_APPROVED("file_not_approved"),
    TRANSFER_CANCELLED("transfer_cancelled"),
}

@ConsistentCopyVisibility
data class FailureContext private constructor(
    val values: Map<String, String>,
) {
    companion object {
        private const val MAX_VALUE_LENGTH = 64

        private val allowedKeys = setOf(
            "protocolVersion",
            "operationId",
            "direction",
            "sizeCategory",
            "lifecycleState",
            "failureCode",
        )

        fun empty(): FailureContext = FailureContext(emptyMap())

        fun fromUntrusted(raw: Map<String, String>): FailureContext {
            val sanitized = raw
                .asSequence()
                .filter { (key, _) -> key in allowedKeys }
                .associate { (key, value) -> key to value.trim().take(MAX_VALUE_LENGTH) }
            return FailureContext(sanitized)
        }
    }
}

data class UserFacingFailure(
    val code: FailureCode,
    val severity: FailureSeverity,
    val recoveryActions: Set<RecoveryAction>,
    val context: FailureContext = FailureContext.empty(),
)
