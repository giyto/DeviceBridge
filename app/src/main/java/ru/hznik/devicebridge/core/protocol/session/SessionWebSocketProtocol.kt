package ru.hznik.devicebridge.core.protocol.session

import kotlinx.serialization.Serializable

const val SESSION_AUTH_MESSAGE_TYPE = "session.auth"
const val SESSION_AUTHENTICATED_MESSAGE_TYPE = "session.authenticated"
const val MAX_SESSION_TOKEN_LENGTH = 256

@Serializable
data class SessionWebSocketAuthMessage(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val token: String,
)

@Serializable
data class SessionWebSocketEventMessage(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
)

enum class SessionWebSocketValidationError {
    NONE,
    UNSUPPORTED_VERSION,
    INVALID_TYPE,
    INVALID_MESSAGE_ID,
    DUPLICATE_MESSAGE_ID,
    INVALID_TIMESTAMP,
    INVALID_TOKEN,
}

object SessionWebSocketAuthValidator {
    fun validate(
        message: SessionWebSocketAuthMessage,
        seenMessageIds: Set<String>,
    ): SessionWebSocketValidationError = when {
        message.protocolVersion != SESSION_PROTOCOL_VERSION ->
            SessionWebSocketValidationError.UNSUPPORTED_VERSION
        message.type != SESSION_AUTH_MESSAGE_TYPE -> SessionWebSocketValidationError.INVALID_TYPE
        !message.messageId.isOpaqueMessageId() -> SessionWebSocketValidationError.INVALID_MESSAGE_ID
        message.messageId in seenMessageIds -> SessionWebSocketValidationError.DUPLICATE_MESSAGE_ID
        message.timestamp <= 0L -> SessionWebSocketValidationError.INVALID_TIMESTAMP
        message.token.isBlank() || message.token.length > MAX_SESSION_TOKEN_LENGTH ||
            message.token.any(Char::isISOControl) -> SessionWebSocketValidationError.INVALID_TOKEN
        else -> SessionWebSocketValidationError.NONE
    }
}

private val MESSAGE_ID = Regex("^[A-Za-z0-9_-]+$")

private fun String.isOpaqueMessageId(): Boolean =
    length in 1..MAX_CHALLENGE_ID_LENGTH && MESSAGE_ID.matches(this)
