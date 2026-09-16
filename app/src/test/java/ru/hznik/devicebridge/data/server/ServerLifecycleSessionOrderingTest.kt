package ru.hznik.devicebridge.data.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleState

class ServerLifecycleSessionOrderingTest {

    @Test
    fun sessionGenerationActivatesOnlyAfterListenerStarts() = runBlocking {
        val runtime = OrderedRuntime()
        val coordinator = ServerLifecycleCoordinator(
            ServerRuntimeFactory { runtime },
            MonotonicClock { 10 },
        )

        coordinator.start()

        assertEquals(listOf("listener.start", "session.activate:1"), runtime.events)
        assertTrue(coordinator.state.value is ServerLifecycleState.Running)
    }

    @Test
    fun bindFailureNeverActivatesSessionAndCleansRuntime() = runBlocking {
        val runtime = OrderedRuntime(failStart = true)
        val coordinator = ServerLifecycleCoordinator(
            ServerRuntimeFactory { runtime },
            MonotonicClock { 10 },
        )

        coordinator.start()

        assertEquals(listOf("listener.start", "session.close", "listener.stop"), runtime.events)
        assertTrue(coordinator.state.value is ServerLifecycleState.Error)
    }

    @Test
    fun stopClosesSessionGenerationBeforeCioListener() = runBlocking {
        val runtime = OrderedRuntime()
        val coordinator = ServerLifecycleCoordinator(
            ServerRuntimeFactory { runtime },
            MonotonicClock { 10 },
        )
        coordinator.start()

        coordinator.stop()

        assertEquals(
            listOf("listener.start", "session.activate:1", "session.close", "listener.stop"),
            runtime.events,
        )
        assertEquals(ServerLifecycleState.Stopped, coordinator.state.value)
    }

    private class OrderedRuntime(
        private val failStart: Boolean = false,
    ) : ServerRuntime {
        val events = mutableListOf<String>()

        override suspend fun start(): ServerEndpoint {
            events += "listener.start"
            if (failStart) error("bind failed")
            return ServerEndpoint("192.168.1.24", 8787)
        }

        override suspend fun activateSessionGeneration(generation: Long) {
            events += "session.activate:$generation"
        }

        override suspend fun closeSessionGeneration() {
            events += "session.close"
        }

        override suspend fun stop() {
            events += "listener.stop"
        }
    }
}
