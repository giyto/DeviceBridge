package ru.hznik.devicebridge.data.server

import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleError

interface ServerRuntime {
    val networkFingerprint: String?
        get() = null

    suspend fun start(): ServerEndpoint

    suspend fun activateSessionGeneration(generation: Long) = Unit

    suspend fun closeSessionGeneration() = Unit

    suspend fun stop()
}

fun interface ServerRuntimeFactory {
    fun create(): ServerRuntime
}

class ServerRuntimeStartException(
    val lifecycleError: ServerLifecycleError,
) : IllegalStateException(lifecycleError.toString())
