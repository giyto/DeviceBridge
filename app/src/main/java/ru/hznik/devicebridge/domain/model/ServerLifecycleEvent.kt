package ru.hznik.devicebridge.domain.model

sealed interface ServerLifecycleEvent {
    val generation: Long

    data class StartRequested(
        override val generation: Long,
    ) : ServerLifecycleEvent

    data class Started(
        override val generation: Long,
        val endpoint: ServerEndpoint,
        val startedAtElapsedRealtimeMs: Long,
    ) : ServerLifecycleEvent

    data class StopRequested(
        override val generation: Long,
    ) : ServerLifecycleEvent

    data class Stopped(
        override val generation: Long,
    ) : ServerLifecycleEvent

    data class Failed(
        override val generation: Long,
        val cause: ServerLifecycleError,
    ) : ServerLifecycleEvent
}
