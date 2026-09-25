package ru.hznik.devicebridge.core.protocol.text

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.hznik.devicebridge.core.protocol.error.toFailureCode
import ru.hznik.devicebridge.domain.text.TextContentValidation
import ru.hznik.devicebridge.domain.text.TextContentValidator

const val TEXT_PROTOCOL_VERSION = 1
const val TEXT_SEND_TYPE = "text.send"
const val TEXT_ACCEPTED_TYPE = "text.accepted"
const val TEXT_RECEIVED_TYPE = "text.received"
const val TEXT_ACK_TYPE = "text.ack"
const val TEXT_SNAPSHOT_TYPE = "text.snapshot"
const val TEXT_ERROR_TYPE = "text.error"
const val MAX_TEXT_SNAPSHOT_ITEMS = 100
const val MAX_TEXT_JSON_BYTES = TextContentValidator.MAX_UTF8_BYTES + 4 * 1024

private const val MAX_TEXT_PROTOCOL_ID_LENGTH = 64
private const val MAX_TEXT_SENDER_LABEL_LENGTH = 64
private val TEXT_PROTOCOL_ID = Regex("^[A-Za-z0-9_-]+$")

@Serializable
enum class TextContentKindDto {
    TEXT,
    LINK,
}

@Serializable
enum class TextDirectionDto {
    ANDROID_TO_BROWSER,
    BROWSER_TO_ANDROID,
}

@Serializable
enum class TextTransferStatusDto {
    PENDING,
    SENDING,
    DELIVERED,
    FAILED,
}

@Serializable
enum class TextProtocolErrorCode {
    INVALID_PAYLOAD,
    UNSUPPORTED_VERSION,
    CONTENT_TOO_LARGE,
    MESSAGE_CONFLICT,
    SESSION_UNAVAILABLE,
}

@Serializable
data class TextSendRequest(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val content: String,
)

@Serializable
data class TextAcceptedResponse(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val contentKind: TextContentKindDto,
    val status: TextTransferStatusDto,
)

@Serializable
data class TextReceivedEvent(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val content: String,
    val contentKind: TextContentKindDto,
    val direction: TextDirectionDto,
    val senderLabel: String,
    val status: TextTransferStatusDto,
)

@Serializable
data class TextAcknowledgementMessage(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val acknowledgedMessageId: String,
)

@Serializable
data class TextSnapshotItem(
    val messageId: String,
    val timestamp: Long,
    val content: String,
    val contentKind: TextContentKindDto,
    val direction: TextDirectionDto,
    val senderLabel: String,
    val status: TextTransferStatusDto,
)

@Serializable
data class TextSnapshotEvent(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val items: List<TextSnapshotItem>,
)

@Serializable
data class TextErrorEvent(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val timestamp: Long,
    val relatedMessageId: String? = null,
    val code: TextProtocolErrorCode,
    val errorCode: String = code.toFailureCode().wireValue,
)

fun TextReceivedEvent.toSnapshotItem(): TextSnapshotItem = TextSnapshotItem(
    messageId = messageId,
    timestamp = timestamp,
    content = content,
    contentKind = contentKind,
    direction = direction,
    senderLabel = senderLabel,
    status = status,
)

object TextProtocolJson {
    val format: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    inline fun <reified T> encode(value: T): String = format.encodeToString(value)

    inline fun <reified T> decode(value: String): T = format.decodeFromString(value)
}

enum class TextProtocolValidationError {
    NONE,
    UNSUPPORTED_VERSION,
    INVALID_TYPE,
    INVALID_MESSAGE_ID,
    INVALID_TIMESTAMP,
    EMPTY_CONTENT,
    CONTENT_TOO_LARGE,
    INVALID_ACKNOWLEDGED_MESSAGE_ID,
    INVALID_SENDER_LABEL,
    INVALID_SNAPSHOT,
    INVALID_RELATED_MESSAGE_ID,
}

object TextProtocolValidator {
    fun validate(message: TextSendRequest): TextProtocolValidationError =
        validateEnvelope(message.protocolVersion, message.messageId, message.type, message.timestamp, TEXT_SEND_TYPE)
            .takeUnless { it == TextProtocolValidationError.NONE }
            ?: validateContent(message.content)

    fun validate(message: TextAcceptedResponse): TextProtocolValidationError =
        validateEnvelope(
            message.protocolVersion,
            message.messageId,
            message.type,
            message.timestamp,
            TEXT_ACCEPTED_TYPE,
        )

    fun validate(message: TextReceivedEvent): TextProtocolValidationError {
        val envelope = validateEnvelope(
            message.protocolVersion,
            message.messageId,
            message.type,
            message.timestamp,
            TEXT_RECEIVED_TYPE,
        )
        if (envelope != TextProtocolValidationError.NONE) return envelope
        val content = validateContent(message.content)
        if (content != TextProtocolValidationError.NONE) return content
        return validateSenderLabel(message.senderLabel)
    }

    fun validate(message: TextAcknowledgementMessage): TextProtocolValidationError {
        val envelope = validateEnvelope(
            message.protocolVersion,
            message.messageId,
            message.type,
            message.timestamp,
            TEXT_ACK_TYPE,
        )
        if (envelope != TextProtocolValidationError.NONE) return envelope
        return if (message.acknowledgedMessageId.isValidProtocolId()) {
            TextProtocolValidationError.NONE
        } else {
            TextProtocolValidationError.INVALID_ACKNOWLEDGED_MESSAGE_ID
        }
    }

    fun validate(message: TextSnapshotEvent): TextProtocolValidationError {
        val envelope = validateEnvelope(
            message.protocolVersion,
            message.messageId,
            message.type,
            message.timestamp,
            TEXT_SNAPSHOT_TYPE,
        )
        if (envelope != TextProtocolValidationError.NONE) return envelope
        if (message.items.size > MAX_TEXT_SNAPSHOT_ITEMS) {
            return TextProtocolValidationError.INVALID_SNAPSHOT
        }
        return message.items.asSequence()
            .map(::validateSnapshotItem)
            .firstOrNull { it != TextProtocolValidationError.NONE }
            ?: TextProtocolValidationError.NONE
    }

    fun validate(message: TextErrorEvent): TextProtocolValidationError {
        val envelope = validateEnvelope(
            message.protocolVersion,
            message.messageId,
            message.type,
            message.timestamp,
            TEXT_ERROR_TYPE,
        )
        if (envelope != TextProtocolValidationError.NONE) return envelope
        return if (message.relatedMessageId == null || message.relatedMessageId.isValidProtocolId()) {
            TextProtocolValidationError.NONE
        } else {
            TextProtocolValidationError.INVALID_RELATED_MESSAGE_ID
        }
    }

    private fun validateSnapshotItem(item: TextSnapshotItem): TextProtocolValidationError {
        if (!item.messageId.isValidProtocolId()) return TextProtocolValidationError.INVALID_SNAPSHOT
        if (item.timestamp <= 0) return TextProtocolValidationError.INVALID_SNAPSHOT
        val content = validateContent(item.content)
        if (content != TextProtocolValidationError.NONE) return TextProtocolValidationError.INVALID_SNAPSHOT
        return validateSenderLabel(item.senderLabel)
    }

    private fun validateEnvelope(
        protocolVersion: Int,
        messageId: String,
        type: String,
        timestamp: Long,
        expectedType: String,
    ): TextProtocolValidationError = when {
        protocolVersion != TEXT_PROTOCOL_VERSION -> TextProtocolValidationError.UNSUPPORTED_VERSION
        type != expectedType -> TextProtocolValidationError.INVALID_TYPE
        !messageId.isValidProtocolId() -> TextProtocolValidationError.INVALID_MESSAGE_ID
        timestamp <= 0 -> TextProtocolValidationError.INVALID_TIMESTAMP
        else -> TextProtocolValidationError.NONE
    }

    private fun validateContent(content: String): TextProtocolValidationError =
        when (TextContentValidator.validate(content)) {
            TextContentValidation.Empty -> TextProtocolValidationError.EMPTY_CONTENT
            is TextContentValidation.TooLarge -> TextProtocolValidationError.CONTENT_TOO_LARGE
            is TextContentValidation.Valid -> TextProtocolValidationError.NONE
        }

    private fun validateSenderLabel(label: String): TextProtocolValidationError =
        if (
            label.isNotBlank() &&
            label.length <= MAX_TEXT_SENDER_LABEL_LENGTH &&
            label.none(Char::isISOControl)
        ) {
            TextProtocolValidationError.NONE
        } else {
            TextProtocolValidationError.INVALID_SENDER_LABEL
        }

    private fun String.isValidProtocolId(): Boolean =
        length in 1..MAX_TEXT_PROTOCOL_ID_LENGTH && TEXT_PROTOCOL_ID.matches(this)
}
