package ru.hznik.devicebridge.domain.usecase

import ru.hznik.devicebridge.domain.repository.ServerLifecycleRepository

class StartServerUseCase(
    private val repository: ServerLifecycleRepository,
) {
    suspend operator fun invoke() = repository.start()
}
