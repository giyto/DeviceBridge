package ru.hznik.devicebridge.core.protocol.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.hznik.devicebridge.core.protocol.error.ProtocolErrorDetails
import ru.hznik.devicebridge.core.protocol.error.toFailureCode

const val SESSION_PROTOCOL_VERSION = 1
const val MAX_SESSION_JSON_BYTES = 4 * 1024
const val MAX_CLIENT_LABEL_LENGTH = 64
const val MAX_CHALLENGE_ID_LENGTH = 64
const val MAX_TRUSTED_CREDENTIAL_LENGTH = 256

@Serializable
data class SessionChallengeRequest(
    val protocolVersion: Int,
    val clientLabel: String,
    val rememberBrowserRequested: Boolean = false,
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
data class SessionConfirmationStatusRequest(
    val protocolVersion: Int,
    val challengeId: String,
    val clientLabel: String,
)

@Serializable
enum class SessionConfirmationStatusState {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED,
}

@Serializable
data class SessionConfirmationStatusResponse(
    val protocolVersion: Int,
    val state: SessionConfirmationStatusState,
    val sessionId: String? = null,
    val token: String? = null,
    val serverTimeEpochMillis: Long? = null,
    val trustedCredential: String? = null,
    val trustedCredentialExpiresAtEpochMillis: Long? = null,
)
@Serializable
data class SessionConfirmResponse(
    val protocolVersion: Int,
    val sessionId: String,
    val token: String,
    val serverTimeEpochMillis: Long,
    val trustedCredential: String? = null,
    val trustedCredentialExpiresAtEpochMillis: Long? = null,
)

@Serializable
data class TrustedSessionExchangeRequest(
    val protocolVersion: Int,
    val trustedCredential: String,
)

@Serializable
data class SessionStatusResponse(
    val protocolVersion: Int,
    val sessionId: String,
    val connected: Boolean,
    val activeSessionCount: Int,
    val effectiveFileLimitBytes: Long,
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
    val errorCode: String = code.toFailureCode().wireValue,
    val details: ProtocolErrorDetails? = null,
)

@Serializable
enum class SessionErrorCode {
    INVALID_PAYLOAD,
    UNSUPPORTED_VERSION,
    INVALID_CODE,
    INVALID_TRUSTED_CREDENTIAL,
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
    INVALID_TRUSTED_CREDENTIAL,
}

object SessionPayloadValidator {
    fun validate(request: SessionChallengeRequest): SessionValidationError = when {
        request.protocolVersion != SESSION_PROTOCOL_VERSION -> SessionValidationError.UNSUPPORTED_VERSION
        !request.clientLabel.isValidBoundedText(MAX_CLIENT_LABEL_LENGTH) ->
            SessionValidationError.INVALID_CLIENT_LABEL
        else -> SessionValidationError.NONE
    }

    fun validate(request: SessionConfirmationStatusRequest): SessionValidationError = when {
        request.protocolVersion != SESSION_PROTOCOL_VERSION -> SessionValidationError.UNSUPPORTED_VERSION
        !request.challengeId.isValidOpaqueId(MAX_CHALLENGE_ID_LENGTH) ->
            SessionValidationError.INVALID_CHALLENGE_ID
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

    fun validate(request: TrustedSessionExchangeRequest): SessionValidationError = when {
        request.protocolVersion != SESSION_PROTOCOL_VERSION -> SessionValidationError.UNSUPPORTED_VERSION
        !request.trustedCredential.isValidOpaqueId(MAX_TRUSTED_CREDENTIAL_LENGTH) ->
            SessionValidationError.INVALID_TRUSTED_CREDENTIAL
        else -> SessionValidationError.NONE
    }
}

private val PAIRING_CODE = Regex("^[0-9]{6}$")
private val OPAQUE_ID = Regex("^[A-Za-z0-9_-]+$")

private fun String.isValidOpaqueId(maxLength: Int): Boolean =
    length in 1..maxLength && OPAQUE_ID.matches(this)

private fun String.isValidBoundedText(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && none(Char::isISOControl)
