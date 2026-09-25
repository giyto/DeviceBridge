package ru.hznik.devicebridge.domain.model

sealed interface ServerLifecycleState {
    val isRunning: Boolean
        get() = false

    /** Neither running nor on its way up or down, so a start is allowed. */
    val isIdle: Boolean
        get() = this is Stopped || this is Error

    /** The generation this state belongs to; null only when stopped. */
    fun generationOrNull(): Long? = when (this) {
        Stopped -> null
        is Starting -> generation
        is Running -> generation
        is Stopping -> generation
        is Error -> generation
    }

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
        val generation: Long,
        val cause: ServerLifecycleError,
    ) : ServerLifecycleState
}
