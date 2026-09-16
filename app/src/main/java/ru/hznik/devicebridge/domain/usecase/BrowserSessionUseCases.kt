package ru.hznik.devicebridge.domain.usecase

import kotlinx.coroutines.flow.StateFlow
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingRequestId

class ObserveBrowserSessionsUseCase(
    private val repository: BrowserSessionRepository,
) {
    operator fun invoke(): StateFlow<BrowserSessionState> = repository.state
}

class ApproveBrowserRequestUseCase(
    private val repository: BrowserSessionRepository,
) {
    suspend operator fun invoke(requestId: PairingRequestId) = repository.approve(requestId)
}

class DenyBrowserRequestUseCase(
    private val repository: BrowserSessionRepository,
) {
    suspend operator fun invoke(requestId: PairingRequestId) = repository.deny(requestId)
}

class RevokeBrowserSessionUseCase(
    private val repository: BrowserSessionRepository,
) {
    suspend operator fun invoke(sessionId: BrowserSessionId) = repository.revoke(sessionId)
}
