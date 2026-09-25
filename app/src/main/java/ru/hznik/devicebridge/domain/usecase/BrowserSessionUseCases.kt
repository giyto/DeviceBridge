package ru.hznik.devicebridge.domain.usecase

import kotlinx.coroutines.flow.StateFlow
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository
import ru.hznik.devicebridge.domain.session.BrowserApprovalDecision
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingRequestId

class ObserveBrowserSessionsUseCase(
    private val repository: BrowserSessionRepository,
) {
    operator fun invoke(): StateFlow<BrowserSessionState> = repository.state

    /** Sessions with a live WebSocket right now; a closed tab drops out of this set. */
    fun connectedSessionIds(): StateFlow<Set<BrowserSessionId>> = repository.connectedSessionIds
}

class ApproveBrowserRequestUseCase(
    private val repository: BrowserSessionRepository,
) {
    suspend operator fun invoke(
        requestId: PairingRequestId,
        decision: BrowserApprovalDecision = BrowserApprovalDecision.ALLOW_ONCE,
    ) = when (decision) {
        BrowserApprovalDecision.ALLOW_ONCE -> repository.approve(requestId)
        BrowserApprovalDecision.ALLOW_AND_REMEMBER -> repository.approveAndRemember(requestId)
    }
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
