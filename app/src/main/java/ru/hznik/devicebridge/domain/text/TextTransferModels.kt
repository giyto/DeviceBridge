package ru.hznik.devicebridge.domain.text

import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId

private const val MAX_TEXT_MESSAGE_ID_LENGTH = 64
private const val MAX_TEXT_BROWSER_LABEL_LENGTH = 64
private val OPAQUE_TEXT_MESSAGE_ID = Regex("^[A-Za-z0-9_-]+$")

@JvmInline
value class TextMessageId(val value: String) {
    init {
        require(value.length in 1..MAX_TEXT_MESSAGE_ID_LENGTH) {
            "Message identifier length is invalid"
        }
        require(OPAQUE_TEXT_MESSAGE_ID.matches(value)) {
            "Message identifier must be opaque"
        }
    }
}

enum class TextContentKind {
    TEXT,
    LINK,
}

enum class TextTransferDirection {
    ANDROID_TO_BROWSER,
    BROWSER_TO_ANDROID,
}

enum class TextTransferStatus {
    PENDING,
    SENDING,
    DELIVERED,
    FAILED,
}

enum class TextTransferFailureReason {
    CONNECTION_LOST,
    SESSION_CLOSED,
    PROTOCOL_ERROR,
    UNKNOWN,
}

data class SendTextRequest(
    val sessionId: BrowserSessionId,
    val content: String,
)

data class IncomingTextRequest(
    val id: TextMessageId,
    val generationId: ServerGenerationId,
    val sessionId: BrowserSessionId,
    val browserLabel: String,
    val content: String,
    val requestedAtEpochMillis: Long,
)

enum class TextTransferRejection {
    EMPTY_CONTENT,
    CONTENT_TOO_LARGE,
    SESSION_UNAVAILABLE,
    GENERATION_CLOSED,
    MESSAGE_CONFLICT,
    MESSAGE_NOT_FOUND,
}

sealed interface TextTransferResult {
    data class Accepted(val item: TextTransferItem) : TextTransferResult
    data class Rejected(val reason: TextTransferRejection) : TextTransferResult
}

@ConsistentCopyVisibility
data class TextTransferItem private constructor(
    val id: TextMessageId,
    val generationId: ServerGenerationId,
    val sessionId: BrowserSessionId,
    val browserLabel: String,
    val content: String,
    val contentKind: TextContentKind,
    val direction: TextTransferDirection,
    val status: TextTransferStatus,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val failureReason: TextTransferFailureReason?,
) {
    init {
        require(browserLabel.isNotBlank()) { "Browser label must not be blank" }
        require(browserLabel.length <= MAX_TEXT_BROWSER_LABEL_LENGTH) {
            "Browser label is too long"
        }
        require(browserLabel.none(Char::isISOControl)) {
            "Browser label contains control characters"
        }
        require(createdAtEpochMillis > 0) { "Creation time must be positive" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Update time must not precede creation time"
        }
        require((status == TextTransferStatus.FAILED) == (failureReason != null)) {
            "Only a failed item can have a failure reason"
        }
    }

    fun transitionTo(
        next: TextTransferStatus,
        changedAtEpochMillis: Long,
        failureReason: TextTransferFailureReason? = null,
    ): TextTransferItem {
        require(next in allowedTransitions.getValue(status)) {
            "Cannot transition text item from $status to $next"
        }
        require(changedAtEpochMillis >= updatedAtEpochMillis) {
            "Transition time must not move backwards"
        }
        return copy(
            status = next,
            updatedAtEpochMillis = changedAtEpochMillis,
            failureReason = failureReason,
        )
    }

    companion object {
        fun outgoing(
            id: TextMessageId,
            generationId: ServerGenerationId,
            sessionId: BrowserSessionId,
            browserLabel: String,
            content: String,
            contentKind: TextContentKind,
            createdAtEpochMillis: Long,
        ): TextTransferItem = TextTransferItem(
            id = id,
            generationId = generationId,
            sessionId = sessionId,
            browserLabel = browserLabel,
            content = content,
            contentKind = contentKind,
            direction = TextTransferDirection.ANDROID_TO_BROWSER,
            status = TextTransferStatus.PENDING,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = createdAtEpochMillis,
            failureReason = null,
        )

        fun incoming(
            id: TextMessageId,
            generationId: ServerGenerationId,
            sessionId: BrowserSessionId,
            browserLabel: String,
            content: String,
            contentKind: TextContentKind,
            receivedAtEpochMillis: Long,
        ): TextTransferItem = TextTransferItem(
            id = id,
            generationId = generationId,
            sessionId = sessionId,
            browserLabel = browserLabel,
            content = content,
            contentKind = contentKind,
            direction = TextTransferDirection.BROWSER_TO_ANDROID,
            status = TextTransferStatus.DELIVERED,
            createdAtEpochMillis = receivedAtEpochMillis,
            updatedAtEpochMillis = receivedAtEpochMillis,
            failureReason = null,
        )
    }
}

private val allowedTransitions = mapOf(
    TextTransferStatus.PENDING to setOf(TextTransferStatus.SENDING),
    TextTransferStatus.SENDING to setOf(
        TextTransferStatus.DELIVERED,
        TextTransferStatus.FAILED,
    ),
    TextTransferStatus.DELIVERED to emptySet(),
    TextTransferStatus.FAILED to setOf(TextTransferStatus.SENDING),
)

@ConsistentCopyVisibility
data class TextTransferState private constructor(
    val items: List<TextTransferItem>,
) {
    init {
        require(items.map { it.sessionId to it.id }.distinct().size == items.size) {
            "Text transfer state contains duplicate session message identifiers"
        }
    }

    companion object {
        fun empty(): TextTransferState = TextTransferState(emptyList())

        fun of(items: List<TextTransferItem>): TextTransferState = TextTransferState(items.toList())
    }
}
