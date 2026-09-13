package ru.hznik.devicebridge.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import ru.hznik.devicebridge.domain.model.ServerSessionStatus

class HomeViewModelTest {

    @Test
    fun initialStateIsStoppedAndContainsNoInventedConnectionData() {
        val state = HomeViewModel().uiState.value

        assertSame(ServerSessionStatus.Stopped, state.status)
        assertNull(state.localAddress)
        assertNull(state.pairingCode)
        assertEquals(0, state.connectedBrowserCount)
    }

    @Test
    fun transferActionsAreUnavailableWithoutRunningServerAndBrowser() {
        val state = HomeViewModel().uiState.value

        assertFalse(state.canSendText)
        assertFalse(state.canSendFiles)
    }
}
