package ru.hznik.devicebridge.core.protocol.session

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHttpModelsTest {

    @Test
    fun challengeAndConfirmDtosRoundTripWithProtocolVersion() {
        val challenge = SessionChallengeRequest(
            protocolVersion = SESSION_PROTOCOL_VERSION,
            clientLabel = "Edge on Windows",
            rememberBrowserRequested = true,
        )
        val confirm = SessionConfirmRequest(
            protocolVersion = SESSION_PROTOCOL_VERSION,
            challengeId = "challenge-123",
            code = "004201",
            clientLabel = "Edge on Windows",
        )

        assertEquals(
            challenge,
            SessionProtocolJson.decode<SessionChallengeRequest>(SessionProtocolJson.encode(challenge)),
        )
        assertEquals(
            confirm,
            SessionProtocolJson.decode<SessionConfirmRequest>(SessionProtocolJson.encode(confirm)),
        )
    }

    @Test
    fun successAndErrorResponsesRoundTripWithoutLosingMetadata() {
        val challenge = SessionChallengeResponse(
            protocolVersion = SESSION_PROTOCOL_VERSION,
            challengeId = "challenge-123",
            expiresAtEpochMillis = 123_000L,
            confirmTimeoutSeconds = 60,
            attemptsRemaining = 5,
        )
        val success = SessionConfirmResponse(
            protocolVersion = SESSION_PROTOCOL_VERSION,
            sessionId = "session-123",
            token = "opaque-token",
            serverTimeEpochMillis = 123_456L,
            trustedCredential = "trusted-credential",
            trustedCredentialExpiresAtEpochMillis = 2_715_456L,
        )
        val error = SessionErrorEnvelope(
            error = SessionErrorBody(
                code = SessionErrorCode.RATE_LIMITED,
                message = "Слишком много попыток",
                retryAfterSeconds = 60,
                attemptsRemaining = 0,
            ),
        )

        assertEquals(
            challenge,
            SessionProtocolJson.decode<SessionChallengeResponse>(SessionProtocolJson.encode(challenge)),
        )
        assertEquals(
            success,
            SessionProtocolJson.decode<SessionConfirmResponse>(SessionProtocolJson.encode(success)),
        )
        assertEquals(
            error,
            SessionProtocolJson.decode<SessionErrorEnvelope>(SessionProtocolJson.encode(error)),
        )
    }

    @Test
    fun legacyChallengeDefaultsToNoPersistentTrustAndOrdinaryResponseOmitsCredential() {
        val legacy = SessionProtocolJson.decode<SessionChallengeRequest>(
            """{"protocolVersion":1,"clientLabel":"Chrome"}""",
        )
        val ordinary = SessionConfirmResponse(
            protocolVersion = SESSION_PROTOCOL_VERSION,
            sessionId = "session-ordinary",
            token = "session-token",
            serverTimeEpochMillis = 123_456L,
        )

        assertEquals(false, legacy.rememberBrowserRequested)
        val encoded = SessionProtocolJson.encode(ordinary)
        assertTrue(!encoded.contains("trustedCredential"))
    }

    @Test(expected = SerializationException::class)
    fun strictJsonRejectsUnknownFields() {
        SessionProtocolJson.decode<SessionChallengeRequest>(
            """{"protocolVersion":1,"clientLabel":"Chrome","unexpected":true}""",
        )
    }

    @Test(expected = SerializationException::class)
    fun strictJsonRejectsMissingRequiredFields() {
        SessionProtocolJson.decode<SessionConfirmRequest>(
            """{"protocolVersion":1,"challengeId":"id","code":"123456"}""",
        )
    }

    @Test
    fun validatorRejectsUnsupportedVersionAndOversizedFields() {
        val unsupported = SessionPayloadValidator.validate(
            SessionChallengeRequest(protocolVersion = 2, clientLabel = "Chrome"),
        )
        val oversized = SessionPayloadValidator.validate(
            SessionConfirmRequest(
                protocolVersion = SESSION_PROTOCOL_VERSION,
                challengeId = "c".repeat(MAX_CHALLENGE_ID_LENGTH + 1),
                code = "123456",
                clientLabel = "Chrome",
            ),
        )

        assertEquals(SessionValidationError.UNSUPPORTED_VERSION, unsupported)
        assertEquals(SessionValidationError.INVALID_CHALLENGE_ID, oversized)
        assertTrue(MAX_SESSION_JSON_BYTES <= 4 * 1024)
    }

    @Test
    fun trustedExchangeRequestIsStrictAndBoundsCredential() {
        val valid = TrustedSessionExchangeRequest(
            protocolVersion = SESSION_PROTOCOL_VERSION,
            trustedCredential = "abc_DEF-123",
        )
        val oversized = valid.copy(
            trustedCredential = "x".repeat(MAX_TRUSTED_CREDENTIAL_LENGTH + 1),
        )

        assertEquals(
            valid,
            SessionProtocolJson.decode<TrustedSessionExchangeRequest>(
                SessionProtocolJson.encode(valid),
            ),
        )
        assertEquals(SessionValidationError.NONE, SessionPayloadValidator.validate(valid))
        assertEquals(
            SessionValidationError.INVALID_TRUSTED_CREDENTIAL,
            SessionPayloadValidator.validate(oversized),
        )
    }
}
