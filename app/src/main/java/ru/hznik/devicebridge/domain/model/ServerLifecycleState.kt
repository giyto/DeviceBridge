package ru.hznik.devicebridge.domain.model

sealed interface ServerLifecycleState {
    val isRunning: Boolean
        get() = false

    data object Stopped : ServerLifecycleState

    data class Starting(
        val generation: Long,
    ) : ServerLifecycleState

    data class Running(
        val generation: Long,
        val endpoint: ServerEndpoint,
        val startedAtElapsedRealtimeMs: Long,
    ) : ServerLifecycleState {
        override val isRunning: Boolean = true
    }

    data class Stopping(
        val generation: Long,
    ) : ServerLifecycleState

    data class Error(
        val generation: Long?,
        val cause: ServerLifecycleError,
    ) : ServerLifecycleState
}
