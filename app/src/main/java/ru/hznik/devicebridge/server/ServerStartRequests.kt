package ru.hznik.devicebridge.server

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Hands a start command from the Quick Settings tile to the visible Home screen when the tile
 * cannot start the server by itself (missing permission or Android refusing a background start).
 */
@Singleton
class ServerStartRequests @Inject constructor() {
    private val mutablePending = MutableStateFlow<Long?>(null)
    private var nextId = 0L

    val pending: StateFlow<Long?> = mutablePending.asStateFlow()

    @Synchronized
    fun request(): Long {
        val id = ++nextId
        mutablePending.value = id
        return id
    }

    fun consume(id: Long) {
        mutablePending.update { current -> if (current == id) null else current }
    }
}
