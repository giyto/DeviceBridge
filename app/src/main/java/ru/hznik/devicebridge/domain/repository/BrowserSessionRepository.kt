package ru.hznik.devicebridge.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingRequestId

interface BrowserSessionRepository {
    val state: StateFlow<BrowserSessionState>

    suspend fun approve(requestId: PairingRequestId)

    suspend fun deny(requestId: PairingRequestId)

    suspend fun revoke(sessionId: BrowserSessionId)
}
