package ru.hznik.devicebridge.core.protocol.text

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextProtocolModelsTest {

    @Test
    fun allTextDtosRoundTripThroughStrictJson() {
        val send = TextSendRequest(
            protocolVersion = TEXT_PROTOCOL_VERSION,
            messageId = "message-1",
            type = TEXT_SEND_TYPE,
            timestamp = 1_000,
            content = "Привет",
        )
        val accepted = TextAcceptedResponse(
            protocolVersion = TEXT_PROTOCOL_VERSION,
            messageId = send.messageId,
            type = TEXT_ACCEPTED_TYPE,
            timestamp = 1_100,
            contentKind = TextContentKindDto.TEXT,
            status = TextTransferStatusDto.DELIVERED,
        )
        val received = TextReceivedEvent(
            protocolVersion = TEXT_PROTOCOL_VERSION,
            messageId = "message-2",
            type = TEXT_RECEIVED_TYPE,
            timestamp = 1_200,
            content = "https://example.com",
            contentKind = TextContentKindDto.LINK,
            direction = TextDirectionDto.ANDROID_TO_BROWSER,
            senderLabel = "Android",
            status = TextTransferStatusDto.SENDING,
        )
        val ack = TextAcknowledgementMessage(
            protocolVersion = TEXT_PROTOCOL_VERSION,
            messageId = "event-1",
            type = TEXT_ACK_TYPE,
            timestamp = 1_300,
            acknowledgedMessageId = received.messageId,
        )
        val snapshot = TextSnapshotEvent(
            protocolVersion = TEXT_PROTOCOL_VERSION,
            messageId = "event-2",
            type = TEXT_SNAPSHOT_TYPE,
            timestamp = 1_400,
            items = listOf(received.toSnapshotItem()),
        )
        val error = TextErrorEvent(
            protocolVersion = TEXT_PROTOCOL_VERSION,
            messageId = "event-3",
            type = TEXT_ERROR_TYPE,
            timestamp = 1_500,
            relatedMessageId = received.messageId,
            code = TextProtocolErrorCode.MESSAGE_CONFLICT,
        )

        assertEquals(send, TextProtocolJson.decode<TextSendRequest>(TextProtocolJson.encode(send)))
        assertEquals(
            accepted,
            TextProtocolJson.decode<TextAcceptedResponse>(TextProtocolJson.encode(accepted)),
        )
        assertEquals(
            received,
            TextProtocolJson.decode<TextReceivedEvent>(TextProtocolJson.encode(received)),
        )
        assertEquals(
            ack,
            TextProtocolJson.decode<TextAcknowledgementMessage>(TextProtocolJson.encode(ack)),
        )
        assertEquals(
            snapshot,
            TextProtocolJson.decode<TextSnapshotEvent>(TextProtocolJson.encode(snapshot)),
        )
        assertEquals(error, TextProtocolJson.decode<TextErrorEvent>(TextProtocolJson.encode(error)))
    }

    @Test
    fun strictJsonRejectsUnknownFields() {
        val payload = """{
            "protocolVersion":1,
            "messageId":"message-1",
            "type":"text.send",
            "timestamp":1000,
            "content":"hello",
            "unexpected":true
        }""".trimIndent()

        assertThrows(SerializationException::class.java) {
            TextProtocolJson.decode<TextSendRequest>(payload)
        }
    }

    @Test
    fun sendValidatorChecksVersionTypeIdTimestampAndContent() {
        val valid = TextSendRequest(
            protocolVersion = TEXT_PROTOCOL_VERSION,
            messageId = "message-1",
            type = TEXT_SEND_TYPE,
            timestamp = 1_000,
            content = "hello",
        )

        assertEquals(TextProtocolValidationError.NONE, TextProtocolValidator.validate(valid))
        assertEquals(
            TextProtocolValidationError.UNSUPPORTED_VERSION,
            TextProtocolValidator.validate(valid.copy(protocolVersion = 2)),
        )
        assertEquals(
            TextProtocolValidationError.INVALID_TYPE,
            TextProtocolValidator.validate(valid.copy(type = "wrong")),
        )
        assertEquals(
            TextProtocolValidationError.INVALID_MESSAGE_ID,
            TextProtocolValidator.validate(valid.copy(messageId = "bad id")),
        )
        assertEquals(
            TextProtocolValidationError.INVALID_TIMESTAMP,
            TextProtocolValidator.validate(valid.copy(timestamp = 0)),
        )
        assertEquals(
            TextProtocolValidationError.EMPTY_CONTENT,
            TextProtocolValidator.validate(valid.copy(content = " \n")),
        )
        assertEquals(
            TextProtocolValidationError.CONTENT_TOO_LARGE,
            TextProtocolValidator.validate(valid.copy(content = "a".repeat(102_401))),
        )
    }

    @Test
    fun acknowledgementSnapshotAndEventsHaveTypedValidation() {
        val received = validReceived()
        val ack = TextAcknowledgementMessage(
            protocolVersion = 1,
            messageId = "event-1",
            type = TEXT_ACK_TYPE,
            timestamp = 1_100,
            acknowledgedMessageId = received.messageId,
        )
        val snapshot = TextSnapshotEvent(
            protocolVersion = 1,
            messageId = "event-2",
            type = TEXT_SNAPSHOT_TYPE,
            timestamp = 1_200,
            items = listOf(received.toSnapshotItem()),
        )

        assertEquals(TextProtocolValidationError.NONE, TextProtocolValidator.validate(received))
        assertEquals(TextProtocolValidationError.NONE, TextProtocolValidator.validate(ack))
        assertEquals(TextProtocolValidationError.NONE, TextProtocolValidator.validate(snapshot))
        assertEquals(
            TextProtocolValidationError.INVALID_ACKNOWLEDGED_MESSAGE_ID,
            TextProtocolValidator.validate(ack.copy(acknowledgedMessageId = "bad id")),
        )
        assertEquals(
            TextProtocolValidationError.INVALID_SNAPSHOT,
            TextProtocolValidator.validate(snapshot.copy(items = List(101) { received.toSnapshotItem() })),
        )
        assertEquals(
            TextProtocolValidationError.INVALID_SENDER_LABEL,
            TextProtocolValidator.validate(received.copy(senderLabel = "bad\nlabel")),
        )
    }

    private fun validReceived() = TextReceivedEvent(
        protocolVersion = 1,
        messageId = "message-2",
        type = TEXT_RECEIVED_TYPE,
        timestamp = 1_000,
        content = "hello",
        contentKind = TextContentKindDto.TEXT,
        direction = TextDirectionDto.ANDROID_TO_BROWSER,
        senderLabel = "Android",
        status = TextTransferStatusDto.SENDING,
    )
}
