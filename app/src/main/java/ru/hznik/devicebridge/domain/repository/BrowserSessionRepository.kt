package ru.hznik.devicebridge.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId

interface BrowserSessionRepository {
    val state: StateFlow<BrowserSessionState>

    /**
     * Sessions that currently hold at least one live WebSocket connection. A session whose
     * browser tab was closed stays in [state] (it can reconnect) but leaves this set.
     */
    val connectedSessionIds: StateFlow<Set<BrowserSessionId>>

    suspend fun approve(requestId: PairingRequestId)

    suspend fun approveAndRemember(requestId: PairingRequestId) = approve(requestId)

    suspend fun deny(requestId: PairingRequestId)

    suspend fun revoke(sessionId: BrowserSessionId)

    suspend fun revokeTrustedBrowser(browserId: TrustedBrowserId): Boolean = false

    suspend fun revokeAllTrustedBrowsers(): Int = 0
}
