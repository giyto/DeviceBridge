package ru.hznik.devicebridge.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingRequestId

class BrowserSessionUseCasesTest {

    @Test
    fun observeReturnsTheRepositoryStateFlow() {
        val repository = FakeBrowserSessionRepository()

        assertSame(repository.state, ObserveBrowserSessionsUseCase(repository)())
    }

    @Test
    fun hostActionsDelegateOnlyTypedIdentifiers() = runBlocking {
        val repository = FakeBrowserSessionRepository()
        val requestId = PairingRequestId("request-7")
        val sessionId = BrowserSessionId("session-4")

        ApproveBrowserRequestUseCase(repository)(requestId)
        DenyBrowserRequestUseCase(repository)(requestId)
        RevokeBrowserSessionUseCase(repository)(sessionId)

        assertEquals(listOf(requestId), repository.approved)
        assertEquals(listOf(requestId), repository.denied)
        assertEquals(listOf(sessionId), repository.revoked)
    }

    private class FakeBrowserSessionRepository : BrowserSessionRepository {
        override val state: StateFlow<BrowserSessionState> =
            MutableStateFlow(BrowserSessionState.inactive())
        val approved = mutableListOf<PairingRequestId>()
        val denied = mutableListOf<PairingRequestId>()
        val revoked = mutableListOf<BrowserSessionId>()

        override suspend fun approve(requestId: PairingRequestId) {
            approved += requestId
        }

        override suspend fun deny(requestId: PairingRequestId) {
            denied += requestId
        }

        override suspend fun revoke(sessionId: BrowserSessionId) {
            revoked += sessionId
        }
    }
}
