package ru.hznik.devicebridge.data.text

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ru.hznik.devicebridge.core.text.sha256Hex
import ru.hznik.devicebridge.domain.repository.TextTransferRepository
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.text.IncomingTextRequest
import ru.hznik.devicebridge.domain.text.SendTextRequest
import ru.hznik.devicebridge.domain.text.TextContentClassifier
import ru.hznik.devicebridge.domain.text.TextContentValidation
import ru.hznik.devicebridge.domain.text.TextContentValidator
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferDirection
import ru.hznik.devicebridge.domain.text.TextTransferFailureReason
import ru.hznik.devicebridge.domain.text.TextTransferItem
import ru.hznik.devicebridge.domain.text.TextTransferRejection
import ru.hznik.devicebridge.domain.text.TextTransferResult
import ru.hznik.devicebridge.domain.text.TextTransferState
import ru.hznik.devicebridge.domain.text.TextTransferStatus

fun interface TextSessionEventGateway {
    suspend fun deliver(item: TextTransferItem): Boolean
}

fun interface TextTerminalHistoryRecorder {
    suspend fun recordTerminal(item: TextTransferItem)
}

class TextTransferCoordinator(
    private val nowEpochMillis: () -> Long,
    private val maxFeedItems: Int = 100,
    private val browserSessionState: () -> BrowserSessionState = { BrowserSessionState.inactive() },
    private val eventGateway: TextSessionEventGateway = TextSessionEventGateway { false },
    private val newMessageId: () -> TextMessageId = {
        TextMessageId(UUID.randomUUID().toString())
    },
    private val acknowledgementTimeoutMs: Long = 10_000,
    private val historyRecorder: TextTerminalHistoryRecorder =
        TextTerminalHistoryRecorder { },
) : TextTransferRepository {
    private data class MessageKey(
        val generationId: ServerGenerationId,
        val sessionId: BrowserSessionId,
        val messageId: TextMessageId,
    )

    private data class StoredOutcome(
        val fingerprint: String,
        val result: TextTransferResult,
    )

    private sealed interface DeliverySignal {
        data object Delivered : DeliverySignal
        data class Failed(val reason: TextTransferFailureReason) : DeliverySignal
    }

    private sealed interface SendPreparation {
        data class Ready(val item: TextTransferItem) : SendPreparation
        data class Rejected(val result: TextTransferResult.Rejected) : SendPreparation
    }

    private val mutex = Mutex()
    private val outcomes = LinkedHashMap<MessageKey, StoredOutcome>()
    private val pendingAcknowledgements =
        LinkedHashMap<MessageKey, CompletableDeferred<DeliverySignal>>()
    private val mutableState = MutableStateFlow(TextTransferState.empty())
    private var activeGenerationId: ServerGenerationId? = null

    init {
        require(maxFeedItems > 0) { "Feed item limit must be positive" }
        require(acknowledgementTimeoutMs > 0) { "Acknowledgement timeout must be positive" }
    }

    override val state: StateFlow<TextTransferState> = mutableState.asStateFlow()

    suspend fun activate(generationId: ServerGenerationId) {
        val interrupted = mutex.withLock { resetLocked(generationId) }
        interrupted.forEach { it.complete(DeliverySignal.Failed(TextTransferFailureReason.SESSION_CLOSED)) }
    }

    suspend fun close(generationId: ServerGenerationId) {
        val interrupted = mutex.withLock {
            if (activeGenerationId != generationId) emptyList() else resetLocked(null)
        }
        interrupted.forEach { it.complete(DeliverySignal.Failed(TextTransferFailureReason.SESSION_CLOSED)) }
    }

    override suspend fun send(request: SendTextRequest): TextTransferResult {
        val preparation = mutex.withLock {
            val generationId = activeGenerationId
                ?: return@withLock SendPreparation.Rejected(
                    TextTransferResult.Rejected(TextTransferRejection.GENERATION_CLOSED),
                )
            val session = browserSessionState().sessions.firstOrNull { candidate ->
                candidate.id == request.sessionId && candidate.generationId == generationId
            } ?: return@withLock SendPreparation.Rejected(
                TextTransferResult.Rejected(TextTransferRejection.SESSION_UNAVAILABLE),
            )
            rejectionFor(request.content)?.let { rejection ->
                return@withLock SendPreparation.Rejected(TextTransferResult.Rejected(rejection))
            }
            val item = TextTransferItem.outgoing(
                id = newMessageId(),
                generationId = generationId,
                sessionId = session.id,
                browserLabel = session.browserLabel,
                content = request.content,
                contentKind = TextContentClassifier.classify(request.content),
                createdAtEpochMillis = nowEpochMillis(),
            )
            appendItemLocked(item)
            SendPreparation.Ready(item)
        }
        return when (preparation) {
            is SendPreparation.Ready -> deliver(preparation.item)
            is SendPreparation.Rejected -> preparation.result
        }
    }

    override suspend fun retry(messageId: TextMessageId): TextTransferResult {
        val item = mutex.withLock {
            mutableState.value.items.firstOrNull { candidate ->
                candidate.id == messageId &&
                    candidate.direction == TextTransferDirection.ANDROID_TO_BROWSER
            }
        } ?: return TextTransferResult.Rejected(TextTransferRejection.MESSAGE_NOT_FOUND)

        if (item.status == TextTransferStatus.DELIVERED) {
            return TextTransferResult.Accepted(item)
        }
        if (item.status != TextTransferStatus.FAILED) {
            return TextTransferResult.Rejected(TextTransferRejection.MESSAGE_CONFLICT)
        }
        val currentSession = browserSessionState().sessions.any { session ->
            session.id == item.sessionId && session.generationId == item.generationId
        }
        if (activeGenerationId != item.generationId || !currentSession) {
            return TextTransferResult.Rejected(TextTransferRejection.SESSION_UNAVAILABLE)
        }
        return deliver(item)
    }

    suspend fun acknowledge(
        generationId: ServerGenerationId,
        sessionId: BrowserSessionId,
        messageId: TextMessageId,
    ): Boolean = mutex.withLock {
        pendingAcknowledgements[MessageKey(generationId, sessionId, messageId)]
            ?.complete(DeliverySignal.Delivered)
            ?: false
    }

    suspend fun onConnectionLost(sessionId: BrowserSessionId) {
        val interrupted = mutex.withLock {
            pendingAcknowledgements
                .filterKeys { it.sessionId == sessionId }
                .values
                .toList()
        }
        interrupted.forEach {
            it.complete(DeliverySignal.Failed(TextTransferFailureReason.CONNECTION_LOST))
        }
    }

    suspend fun snapshotFor(
        generationId: ServerGenerationId,
        sessionId: BrowserSessionId,
    ): List<TextTransferItem> = mutex.withLock {
        if (activeGenerationId != generationId) {
            emptyList()
        } else {
            mutableState.value.items
                .asSequence()
                .filter { it.generationId == generationId && it.sessionId == sessionId }
                .distinctBy(TextTransferItem::id)
                .toList()
        }
    }

    suspend fun acceptIncoming(request: IncomingTextRequest): TextTransferResult {
        val result = mutex.withLock {
            if (activeGenerationId != request.generationId) {
                return@withLock TextTransferResult.Rejected(
                    TextTransferRejection.GENERATION_CLOSED,
                )
            }

            val key = MessageKey(request.generationId, request.sessionId, request.id)
            val fingerprint = request.content.sha256Hex()
            outcomes[key]?.let { stored ->
                return@withLock if (stored.fingerprint == fingerprint) {
                    stored.result
                } else {
                    TextTransferResult.Rejected(TextTransferRejection.MESSAGE_CONFLICT)
                }
            }

            rejectionFor(request.content)?.let { rejection ->
                return@withLock TextTransferResult.Rejected(rejection)
            }

            val item = TextTransferItem.incoming(
                id = request.id,
                generationId = request.generationId,
                sessionId = request.sessionId,
                browserLabel = request.browserLabel,
                content = request.content,
                contentKind = TextContentClassifier.classify(request.content),
                receivedAtEpochMillis = nowEpochMillis(),
            )
            val accepted = TextTransferResult.Accepted(item)
            outcomes[key] = StoredOutcome(fingerprint, accepted)
            appendItemLocked(item)
            accepted
        }
        recordTerminalBestEffort(result)
        return result
    }

    private suspend fun deliver(item: TextTransferItem): TextTransferResult {
        val key = MessageKey(item.generationId, item.sessionId, item.id)
        val deferred = CompletableDeferred<DeliverySignal>()
        val sending = mutex.withLock {
            if (activeGenerationId != item.generationId) {
                return TextTransferResult.Rejected(TextTransferRejection.GENERATION_CLOSED)
            }
            val current = findItemLocked(item.sessionId, item.id)
                ?: return TextTransferResult.Rejected(TextTransferRejection.MESSAGE_NOT_FOUND)
            val next = current.transitionTo(
                next = TextTransferStatus.SENDING,
                changedAtEpochMillis = nowEpochMillis(),
            )
            pendingAcknowledgements[key] = deferred
            replaceItemLocked(next)
            next
        }

        val eventAccepted = runCatching { eventGateway.deliver(sending) }.getOrDefault(false)
        val signal = if (!eventAccepted) {
            DeliverySignal.Failed(TextTransferFailureReason.SESSION_CLOSED)
        } else {
            withTimeoutOrNull(acknowledgementTimeoutMs) { deferred.await() }
                ?: DeliverySignal.Failed(TextTransferFailureReason.CONNECTION_LOST)
        }

        val result = mutex.withLock {
            pendingAcknowledgements.remove(key, deferred)
            val current = findItemLocked(item.sessionId, item.id)
                ?: return@withLock TextTransferResult.Rejected(
                    if (activeGenerationId != item.generationId) {
                        TextTransferRejection.GENERATION_CLOSED
                    } else {
                        TextTransferRejection.MESSAGE_NOT_FOUND
                    },
                )
            if (current.status != TextTransferStatus.SENDING) {
                return@withLock TextTransferResult.Accepted(current)
            }
            val completed = when (signal) {
                DeliverySignal.Delivered -> current.transitionTo(
                    next = TextTransferStatus.DELIVERED,
                    changedAtEpochMillis = nowEpochMillis(),
                )
                is DeliverySignal.Failed -> current.transitionTo(
                    next = TextTransferStatus.FAILED,
                    changedAtEpochMillis = nowEpochMillis(),
                    failureReason = signal.reason,
                )
            }
            replaceItemLocked(completed)
            TextTransferResult.Accepted(completed)
        }
        recordTerminalBestEffort(result)
        return result
    }

    private suspend fun recordTerminalBestEffort(result: TextTransferResult) {
        val item = (result as? TextTransferResult.Accepted)?.item ?: return
        if (!item.status.isTerminal) return
        runCatching {
            historyRecorder.recordTerminal(item)
        }
    }

    private fun resetLocked(
        generationId: ServerGenerationId?,
    ): List<CompletableDeferred<DeliverySignal>> {
        val pending = pendingAcknowledgements.values.toList()
        activeGenerationId = generationId
        pendingAcknowledgements.clear()
        outcomes.clear()
        mutableState.value = TextTransferState.empty()
        return pending
    }

    private fun rejectionFor(content: String): TextTransferRejection? =
        when (TextContentValidator.validate(content)) {
            TextContentValidation.Empty -> TextTransferRejection.EMPTY_CONTENT
            is TextContentValidation.TooLarge -> TextTransferRejection.CONTENT_TOO_LARGE
            is TextContentValidation.Valid -> null
        }

    private fun appendItemLocked(item: TextTransferItem) {
        mutableState.value = TextTransferState.of(
            TextFeedPolicy.retainBounded(
                items = mutableState.value.items + item,
                maxCompletedItems = maxFeedItems,
            ),
        )
    }

    private fun replaceItemLocked(item: TextTransferItem) {
        val updated = mutableState.value.items.map { current ->
            if (current.sessionId == item.sessionId && current.id == item.id) item else current
        }
        mutableState.value = TextTransferState.of(
            TextFeedPolicy.retainBounded(updated, maxFeedItems),
        )
    }

    private fun findItemLocked(
        sessionId: BrowserSessionId,
        messageId: TextMessageId,
    ): TextTransferItem? = mutableState.value.items.firstOrNull {
        it.sessionId == sessionId && it.id == messageId
    }
}

internal object TextFeedPolicy {
    fun retainBounded(
        items: List<TextTransferItem>,
        maxCompletedItems: Int,
    ): List<TextTransferItem> {
        require(maxCompletedItems > 0) { "Completed item limit must be positive" }
        var completedToRemove = items.count { it.isCompleted() } - maxCompletedItems
        if (completedToRemove <= 0) return items.toList()

        return items.filter { item ->
            if (completedToRemove > 0 && item.isCompleted()) {
                completedToRemove -= 1
                false
            } else {
                true
            }
        }
    }

    private fun TextTransferItem.isCompleted(): Boolean = status.isTerminal
}
