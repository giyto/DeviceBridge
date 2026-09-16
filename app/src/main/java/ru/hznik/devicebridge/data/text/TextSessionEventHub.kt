package ru.hznik.devicebridge.data.text

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.text.TextTransferItem

fun interface TextSessionEventConnection {
    suspend fun send(item: TextTransferItem): Boolean
}

class TextSessionEventHub(
    private val connectionWaitTimeoutMs: Long = DEFAULT_CONNECTION_WAIT_TIMEOUT_MS,
) : TextSessionEventGateway {
    private val mutex = Mutex()
    private val connections = LinkedHashMap<BrowserSessionId, TextSessionEventConnection>()
    private val connectionState =
        MutableStateFlow<Map<BrowserSessionId, TextSessionEventConnection>>(emptyMap())

    init {
        require(connectionWaitTimeoutMs > 0) { "Connection wait timeout must be positive" }
    }

    suspend fun attach(
        sessionId: BrowserSessionId,
        connection: TextSessionEventConnection,
    ) {
        mutex.withLock {
            connections[sessionId] = connection
            connectionState.value = connections.toMap()
        }
    }

    suspend fun detach(
        sessionId: BrowserSessionId,
        connection: TextSessionEventConnection,
    ): Boolean = mutex.withLock {
        if (connections[sessionId] !== connection) {
            false
        } else {
            connections.remove(sessionId)
            connectionState.value = connections.toMap()
            true
        }
    }

    override suspend fun deliver(item: TextTransferItem): Boolean {
        val connection = awaitConnection(item.sessionId) ?: return false
        return runCatching { connection.send(item) }.getOrDefault(false)
    }

    private suspend fun awaitConnection(
        sessionId: BrowserSessionId,
    ): TextSessionEventConnection? =
        connectionState.value[sessionId] ?: withTimeoutOrNull(connectionWaitTimeoutMs) {
            connectionState
                .map { connections -> connections[sessionId] }
                .filterNotNull()
                .first()
        }

    private companion object {
        const val DEFAULT_CONNECTION_WAIT_TIMEOUT_MS = 2_000L
    }
}
