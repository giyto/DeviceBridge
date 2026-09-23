package ru.hznik.devicebridge.domain.usecase

import kotlinx.coroutines.flow.StateFlow
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.model.ServerStopReason
import ru.hznik.devicebridge.domain.repository.ServerLifecycleRepository

class ObserveServerLifecycleUseCase(
    private val repository: ServerLifecycleRepository,
) {
    operator fun invoke(): StateFlow<ServerLifecycleState> = repository.state

    fun lastStopReason(): StateFlow<ServerStopReason?> = repository.lastStopReason
}
