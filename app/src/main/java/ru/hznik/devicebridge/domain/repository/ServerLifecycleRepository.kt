package ru.hznik.devicebridge.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.model.ServerStopReason

interface ServerLifecycleRepository {
    val state: StateFlow<ServerLifecycleState>

    suspend fun start()

    suspend fun stop(reason: ServerStopReason = ServerStopReason.UserRequested)
}
