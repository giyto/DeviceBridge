package ru.hznik.devicebridge.core.protocol.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionWebSocketProtocolTest {

    @Test
    fun authMessageRoundTripsWithRequiredEnvelopeFields() {
        val message = SessionWebSocketAuthMessage(
            protocolVersion = SESSION_PROTOCOL_VERSION,
            messageId = "message-1",
            type = SESSION_AUTH_MESSAGE_TYPE,
            timestamp = 123_456L,
            token = "opaque-token",
        )

        val encoded = SessionProtocolJson.encode(message)
        val decoded = SessionProtocolJson.decode<SessionWebSocketAuthMessage>(encoded)

        assertEquals(message, decoded)
        assertFalse(encoded.contains("token="))
    }

    @Test
    fun validatorRejectsUnsupportedVersionTypeAndInvalidEnvelope() {
        val base = SessionWebSocketAuthMessage(
            protocolVersion = SESSION_PROTOCOL_VERSION,
            messageId = "message-1",
            type = SESSION_AUTH_MESSAGE_TYPE,
            timestamp = 123_456L,
            token = "opaque-token",
        )

        assertEquals(
            SessionWebSocketValidationError.UNSUPPORTED_VERSION,
            SessionWebSocketAuthValidator.validate(base.copy(protocolVersion = 2), emptySet()),
        )
        assertEquals(
            SessionWebSocketValidationError.INVALID_TYPE,
            SessionWebSocketAuthValidator.validate(base.copy(type = "text.send"), emptySet()),
        )
        assertEquals(
            SessionWebSocketValidationError.INVALID_MESSAGE_ID,
            SessionWebSocketAuthValidator.validate(base.copy(messageId = ""), emptySet()),
        )
        assertEquals(
            SessionWebSocketValidationError.INVALID_TIMESTAMP,
            SessionWebSocketAuthValidator.validate(base.copy(timestamp = 0L), emptySet()),
        )
        assertEquals(
            SessionWebSocketValidationError.INVALID_TOKEN,
            SessionWebSocketAuthValidator.validate(base.copy(token = ""), emptySet()),
        )
    }

    @Test
    fun repeatedControlMessageIdIsRejected() {
        val message = SessionWebSocketAuthMessage(
            protocolVersion = SESSION_PROTOCOL_VERSION,
            messageId = "already-seen",
            type = SESSION_AUTH_MESSAGE_TYPE,
            timestamp = 123_456L,
            token = "opaque-token",
        )

        assertEquals(
            SessionWebSocketValidationError.DUPLICATE_MESSAGE_ID,
            SessionWebSocketAuthValidator.validate(message, setOf("already-seen")),
        )
    }
}
