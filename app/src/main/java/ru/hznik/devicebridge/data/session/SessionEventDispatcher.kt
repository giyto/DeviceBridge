package ru.hznik.devicebridge.data.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ru.hznik.devicebridge.data.text.TextSessionEventGateway
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.text.TextTransferItem

sealed interface SessionOutboundEvent {
    data class Text(val item: TextTransferItem) : SessionOutboundEvent

    data class Control(
        val messageId: String,
        val payload: String,
    ) : SessionOutboundEvent

    data class FileProgress(
        val transferId: FileTransferId,
        val payload: String,
    ) : SessionOutboundEvent

    data class FileTerminal(
        val transferId: FileTransferId,
        val payload: String,
    ) : SessionOutboundEvent
}

fun interface SessionEventConnection {
    suspend fun send(event: SessionOutboundEvent): Boolean
}

class SessionEventDispatcher(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val maxPendingEvents: Int = DEFAULT_MAX_PENDING_EVENTS,
    private val connectionWaitTimeoutMs: Long = DEFAULT_CONNECTION_WAIT_TIMEOUT_MS,
) : TextSessionEventGateway {
    private data class PendingEvent(
        val event: SessionOutboundEvent,
        val completion: CompletableDeferred<Boolean>,
    )

    private val mutex = Mutex()
    private val actors = LinkedHashMap<BrowserSessionId, SessionActor>()
    private val actorState = MutableStateFlow<Map<BrowserSessionId, SessionActor>>(emptyMap())

    init {
        require(maxPendingEvents > 0) { "Pending event limit must be positive" }
        require(connectionWaitTimeoutMs > 0) { "Connection wait timeout must be positive" }
    }

    suspend fun attach(
        sessionId: BrowserSessionId,
        connection: SessionEventConnection,
    ) {
        val replaced = mutex.withLock {
            val actor = SessionActor(connection)
            val previous = actors.put(sessionId, actor)
            actorState.value = actors.toMap()
            previous
        }
        replaced?.close()
    }

    suspend fun detach(
        sessionId: BrowserSessionId,
        connection: SessionEventConnection,
    ): Boolean {
        val removed = mutex.withLock {
            val current = actors[sessionId]
            if (current?.connection !== connection) {
                null
            } else {
                actors.remove(sessionId)
                actorState.value = actors.toMap()
                current
            }
        }
        removed?.close()
        return removed != null
    }

    override suspend fun deliver(item: TextTransferItem): Boolean =
        publish(item.sessionId, SessionOutboundEvent.Text(item))

    suspend fun publish(
        sessionId: BrowserSessionId,
        event: SessionOutboundEvent,
    ): Boolean {
        val actor = awaitActor(sessionId) ?: return false
        return actor.publish(event)
    }

    suspend fun publishIfAttached(
        sessionId: BrowserSessionId,
        event: SessionOutboundEvent,
    ): Boolean {
        val actor = mutex.withLock { actors[sessionId] } ?: return false
        return actor.publish(event)
    }

    private suspend fun awaitActor(sessionId: BrowserSessionId): SessionActor? =
        actorState.value[sessionId] ?: withTimeoutOrNull(connectionWaitTimeoutMs) {
            actorState
                .map { activeActors -> activeActors[sessionId] }
                .filterNotNull()
                .first()
        }

    private inner class SessionActor(
        val connection: SessionEventConnection,
    ) {
        private val queueMutex = Mutex()
        private val queue = mutableListOf<PendingEvent>()
        private val eventSignal = Channel<Unit>(Channel.CONFLATED)
        private val capacitySignal = Channel<Unit>(Channel.CONFLATED)
        private var active = true
        private val worker = scope.launch {
            try {
                while (true) {
                    eventSignal.receive()
                    while (true) {
                        val pending = takeNext() ?: break
                        val delivered = try {
                            connection.send(pending.event)
                        } catch (cancelled: CancellationException) {
                            pending.completion.complete(false)
                            throw cancelled
                        } catch (_: Throwable) {
                            false
                        }
                        pending.completion.complete(delivered)
                    }
                }
            } finally {
                failPending()
            }
        }

        suspend fun publish(event: SessionOutboundEvent): Boolean {
            val completion = CompletableDeferred<Boolean>()
            while (true) {
                var accepted = false
                var closed = false
                queueMutex.withLock {
                    if (!active) {
                        closed = true
                        return@withLock
                    }
                    val coalescingKey = event.progressKey()
                    val sameProgressIndex = coalescingKey?.let { key ->
                        queue.indexOfFirst { pending -> pending.event.progressKey() == key }
                    } ?: -1
                    when {
                        sameProgressIndex >= 0 -> {
                            queue[sameProgressIndex].completion.complete(true)
                            queue[sameProgressIndex] = PendingEvent(event, completion)
                            accepted = true
                        }
                        queue.size < maxPendingEvents -> {
                            queue += PendingEvent(event, completion)
                            accepted = true
                        }
                        event.isPriority() -> {
                            val progressIndex =
                                queue.indexOfFirst { pending -> pending.event.progressKey() != null }
                            if (progressIndex >= 0) {
                                queue.removeAt(progressIndex).completion.complete(true)
                                queue += PendingEvent(event, completion)
                                accepted = true
                            }
                        }
                        coalescingKey != null -> {
                            val progressIndex =
                                queue.indexOfFirst { pending -> pending.event.progressKey() != null }
                            if (progressIndex >= 0) {
                                queue.removeAt(progressIndex).completion.complete(true)
                                queue += PendingEvent(event, completion)
                                accepted = true
                            }
                        }
                    }
                }
                if (closed) return false
                if (accepted) {
                    eventSignal.trySend(Unit)
                    return completion.await()
                }
                capacitySignal.receive()
            }
        }

        suspend fun close() {
            queueMutex.withLock { active = false }
            worker.cancelAndJoin()
        }

        private suspend fun takeNext(): PendingEvent? = queueMutex.withLock {
            if (queue.isEmpty()) return@withLock null
            val priorityIndex = queue.indexOfFirst { pending -> pending.event.isPriority() }
            val pending = queue.removeAt(if (priorityIndex >= 0) priorityIndex else 0)
            capacitySignal.trySend(Unit)
            pending
        }

        private suspend fun failPending() {
            val pending = queueMutex.withLock {
                active = false
                queue.toList().also { queue.clear() }
            }
            pending.forEach { it.completion.complete(false) }
            capacitySignal.close()
            eventSignal.close()
        }
    }

    private companion object {
        const val DEFAULT_MAX_PENDING_EVENTS = 64
        const val DEFAULT_CONNECTION_WAIT_TIMEOUT_MS = 2_000L
    }
}

private fun SessionOutboundEvent.isPriority(): Boolean =
    this !is SessionOutboundEvent.FileProgress

private fun SessionOutboundEvent.progressKey(): FileTransferId? =
    (this as? SessionOutboundEvent.FileProgress)?.transferId
