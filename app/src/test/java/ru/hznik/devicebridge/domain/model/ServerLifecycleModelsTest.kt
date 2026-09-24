package ru.hznik.devicebridge.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLifecycleModelsTest {

    @Test
    fun endpointExposesOnlyConcreteHttpAddress() {
        val endpoint = ServerEndpoint(host = "192.168.1.24", port = 8787)

        assertEquals("http://192.168.1.24:8787", endpoint.url)
        // Secure mode keeps the plain entry address, which moves trusted browsers to HTTPS.
        assertEquals("http://192.168.1.24:8787", endpoint.copy(secure = true).url)
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpoint(host = "", port = 8787)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpoint(host = "192.168.1.24", port = 0)
        }
    }

    @Test
    fun onlyRunningStateReportsAnActiveServer() {
        val endpoint = ServerEndpoint(host = "192.168.1.24", port = 8787)
        val states = listOf(
            ServerLifecycleState.Stopped,
            ServerLifecycleState.Starting(generation = 1),
            ServerLifecycleState.Stopping(generation = 1),
            ServerLifecycleState.Error(
                generation = 1,
                cause = ServerLifecycleError.NoLanNetwork,
            ),
        )

        states.forEach { state -> assertFalse(state.isRunning) }
        assertTrue(
            ServerLifecycleState.Running(
                generation = 1,
                endpoint = endpoint,
                startedAtElapsedRealtimeMs = 2_000,
            ).isRunning,
        )
    }
}
