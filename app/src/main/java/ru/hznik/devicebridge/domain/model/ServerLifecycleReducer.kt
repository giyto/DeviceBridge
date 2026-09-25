package ru.hznik.devicebridge.domain.model

object ServerLifecycleReducer {

    fun reduce(
        current: ServerLifecycleState,
        event: ServerLifecycleEvent,
    ): ServerLifecycleState = when (event) {
        is ServerLifecycleEvent.StartRequested -> when (current) {
            ServerLifecycleState.Stopped,
            is ServerLifecycleState.Error,
            -> ServerLifecycleState.Starting(event.generation)

            else -> current
        }

        is ServerLifecycleEvent.Started -> when (current) {
            is ServerLifecycleState.Starting ->
                if (current.generation == event.generation) {
                    ServerLifecycleState.Running(
                        generation = event.generation,
                        endpoint = event.endpoint,
                        startedAtElapsedRealtimeMs = event.startedAtElapsedRealtimeMs,
                    )
                } else {
                    current
                }

            else -> current
        }

        is ServerLifecycleEvent.EndpointChanged -> when (current) {
            is ServerLifecycleState.Running ->
                if (current.generation == event.generation) current.copy(endpoint = event.endpoint) else current

            else -> current
        }

        is ServerLifecycleEvent.StopRequested -> when (current) {
            is ServerLifecycleState.Starting ->
                current.transitionToStopping(event.generation)

            is ServerLifecycleState.Running ->
                current.transitionToStopping(event.generation)

            else -> current
        }

        is ServerLifecycleEvent.Stopped -> when (current) {
            is ServerLifecycleState.Stopping ->
                if (current.generation == event.generation) {
                    ServerLifecycleState.Stopped
                } else {
                    current
                }

            else -> current
        }

        is ServerLifecycleEvent.Failed -> when (current) {
            is ServerLifecycleState.Starting ->
                current.transitionToError(event)

            is ServerLifecycleState.Running ->
                current.transitionToError(event)

            is ServerLifecycleState.Stopping ->
                current.transitionToError(event)

            else -> current
        }
    }

    private fun ServerLifecycleState.transitionToStopping(
        generation: Long,
    ): ServerLifecycleState =
        if (this.generationOrNull() == generation) {
            ServerLifecycleState.Stopping(generation)
        } else {
            this
        }

    private fun ServerLifecycleState.transitionToError(
        event: ServerLifecycleEvent.Failed,
    ): ServerLifecycleState =
        if (generationOrNull() == event.generation) {
            ServerLifecycleState.Error(event.generation, event.cause)
        } else {
            this
        }

    private fun ServerLifecycleState.generationOrNull(): Long? = when (this) {
        ServerLifecycleState.Stopped -> null
        is ServerLifecycleState.Starting -> generation
        is ServerLifecycleState.Running -> generation
        is ServerLifecycleState.Stopping -> generation
        is ServerLifecycleState.Error -> generation
    }
}
