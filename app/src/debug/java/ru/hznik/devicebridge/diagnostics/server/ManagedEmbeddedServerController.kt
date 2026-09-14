package ru.hznik.devicebridge.diagnostics.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ManagedEmbeddedServerController(
    private val runtimeFactory: DiagnosticServerRuntimeFactory,
    @Suppress("UNUSED_PARAMETER")
    private val tokenGenerator: DiagnosticTokenGenerator = SecureDiagnosticTokenGenerator(),
) : EmbeddedServerController {
    private val mutableState = MutableStateFlow<ServerState>(ServerState.Stopped)
    private val lifecycleMutex = Mutex()
    private var runtime: DiagnosticServerRuntime? = null

    override val state: StateFlow<ServerState> = mutableState.asStateFlow()

    override suspend fun start(preferredPort: Int) {
        lifecycleMutex.withLock {
            if (mutableState.value !== ServerState.Stopped &&
                mutableState.value !is ServerState.Error
            ) {
                return
            }

            mutableState.value = ServerState.Starting
            val token = tokenGenerator.generate()
            try {
                val startedRuntime = runtimeFactory.start(preferredPort, token)
                runtime = startedRuntime
                mutableState.value = ServerState.Running(
                    address = startedRuntime.address,
                    port = startedRuntime.port,
                    token = token,
                )
            } catch (error: CancellationException) {
                mutableState.value = ServerState.Stopped
                throw error
            } catch (error: Exception) {
                runtime = null
                mutableState.value = ServerState.Error(
                    message = error.safeDiagnosticMessage(),
                )
            }
        }
    }

    override suspend fun stop() {
        lifecycleMutex.withLock {
            val runningRuntime = runtime ?: return
            if (mutableState.value !is ServerState.Running) {
                return
            }

            mutableState.value = ServerState.Stopping
            runningRuntime.stop()
            runtime = null
            mutableState.value = ServerState.Stopped
        }
    }

    private fun Exception.safeDiagnosticMessage(): String {
        val detail = message
            ?.replace('\r', ' ')
            ?.replace('\n', ' ')
            ?.trim()
            ?.take(200)
            ?.takeIf(String::isNotEmpty)
            ?: this::class.java.simpleName
        return "Не удалось запустить сервер: $detail"
    }
}
