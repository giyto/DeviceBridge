package ru.hznik.devicebridge.core.protocol.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SESSION_PROTOCOL_VERSION = 1
const val MAX_SESSION_JSON_BYTES = 4 * 1024
const val MAX_CLIENT_LABEL_LENGTH = 64
const val MAX_CHALLENGE_ID_LENGTH = 64

@Serializable
data class SessionChallengeRequest(
    val protocolVersion: Int,
    val clientLabel: String,
)

@Serializable
data class SessionChallengeResponse(
    val protocolVersion: Int,
    val challengeId: String,
    val expiresAtEpochMillis: Long,
    val confirmTimeoutSeconds: Int,
    val attemptsRemaining: Int,
)

@Serializable
data class SessionConfirmRequest(
    val protocolVersion: Int,
    val challengeId: String,
    val code: String,
    val clientLabel: String,
)

@Serializable
data class SessionConfirmResponse(
    val protocolVersion: Int,
    val sessionId: String,
    val token: String,
    val serverTimeEpochMillis: Long,
)

@Serializable
data class SessionStatusResponse(
    val protocolVersion: Int,
    val sessionId: String,
    val connected: Boolean,
    val activeSessionCount: Int,
)

@Serializable
data class SessionErrorEnvelope(
    val error: SessionErrorBody,
)

@Serializable
data class SessionErrorBody(
    val code: SessionErrorCode,
    val message: String,
    val retryAfterSeconds: Int? = null,
    val attemptsRemaining: Int? = null,
)

@Serializable
enum class SessionErrorCode {
    INVALID_PAYLOAD,
    UNSUPPORTED_VERSION,
    INVALID_CODE,
    EXPIRED,
    DENIED,
    RATE_LIMITED,
    CAPACITY_REACHED,
    UNAUTHORIZED,
    SESSION_CLOSED,
}

object SessionProtocolJson {
    val format: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    inline fun <reified T> encode(value: T): String = format.encodeToString(value)

    inline fun <reified T> decode(value: String): T = format.decodeFromString(value)
}

enum class SessionValidationError {
    NONE,
    UNSUPPORTED_VERSION,
    INVALID_CLIENT_LABEL,
    INVALID_CHALLENGE_ID,
    INVALID_CODE,
}

object SessionPayloadValidator {
    fun validate(request: SessionChallengeRequest): SessionValidationError = when {
        request.protocolVersion != SESSION_PROTOCOL_VERSION -> SessionValidationError.UNSUPPORTED_VERSION
        !request.clientLabel.isValidBoundedText(MAX_CLIENT_LABEL_LENGTH) ->
            SessionValidationError.INVALID_CLIENT_LABEL
        else -> SessionValidationError.NONE
    }

    fun validate(request: SessionConfirmRequest): SessionValidationError = when {
        request.protocolVersion != SESSION_PROTOCOL_VERSION -> SessionValidationError.UNSUPPORTED_VERSION
        !request.challengeId.isValidOpaqueId(MAX_CHALLENGE_ID_LENGTH) ->
            SessionValidationError.INVALID_CHALLENGE_ID
        !PAIRING_CODE.matches(request.code) -> SessionValidationError.INVALID_CODE
        !request.clientLabel.isValidBoundedText(MAX_CLIENT_LABEL_LENGTH) ->
            SessionValidationError.INVALID_CLIENT_LABEL
        else -> SessionValidationError.NONE
    }
}

private val PAIRING_CODE = Regex("^[0-9]{6}$")
private val OPAQUE_ID = Regex("^[A-Za-z0-9_-]+$")

private fun String.isValidOpaqueId(maxLength: Int): Boolean =
    length in 1..maxLength && OPAQUE_ID.matches(this)

private fun String.isValidBoundedText(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && none(Char::isISOControl)
