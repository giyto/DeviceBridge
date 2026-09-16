package ru.hznik.devicebridge.data.text

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.text.TextTransferItem

fun interface TextSessionEventConnection {
    suspend fun send(item: TextTransferItem): Boolean
}

class TextSessionEventHub : TextSessionEventGateway {
    private val mutex = Mutex()
    private val connections = LinkedHashMap<BrowserSessionId, TextSessionEventConnection>()

    suspend fun attach(
        sessionId: BrowserSessionId,
        connection: TextSessionEventConnection,
    ) {
        mutex.withLock {
            connections[sessionId] = connection
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
            true
        }
    }

    override suspend fun deliver(item: TextTransferItem): Boolean {
        val connection = mutex.withLock { connections[item.sessionId] } ?: return false
        return runCatching { connection.send(item) }.getOrDefault(false)
    }
}
