package ru.hznik.devicebridge.domain.usecase

import ru.hznik.devicebridge.domain.model.ServerStopReason
import ru.hznik.devicebridge.domain.repository.ServerLifecycleRepository

class StopServerUseCase(
    private val repository: ServerLifecycleRepository,
) {
    suspend operator fun invoke(
        reason: ServerStopReason = ServerStopReason.UserRequested,
    ) = repository.stop(reason)
}
