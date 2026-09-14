package ru.hznik.devicebridge.diagnostics.server

import kotlinx.coroutines.flow.StateFlow

interface EmbeddedServerController {
    val state: StateFlow<ServerState>

    suspend fun start(preferredPort: Int = 0)

    suspend fun stop()
}

interface DiagnosticServerRuntime {
    val address: String
    val port: Int

    suspend fun stop()
}

fun interface DiagnosticServerRuntimeFactory {
    suspend fun start(
        preferredPort: Int,
        token: String,
    ): DiagnosticServerRuntime
}
