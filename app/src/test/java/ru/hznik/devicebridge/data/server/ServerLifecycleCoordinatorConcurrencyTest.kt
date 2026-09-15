package ru.hznik.devicebridge.data.server

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.model.ServerLifecycleState

class ServerLifecycleCoordinatorConcurrencyTest {

    @Test
    fun concurrentStartCommandsCreateOnlyOneRuntime() = runBlocking {
        val factory = RecordingRuntimeFactory()
        val coordinator = ServerLifecycleCoordinator(
            runtimeFactory = factory,
            monotonicClock = MonotonicClock { 1_000 },
        )

        coroutineScope {
            repeat(12) {
                launch(Dispatchers.Default) { coordinator.start() }
            }
        }

        assertEquals(1, factory.createCount.get())
        assertTrue(coordinator.state.value is ServerLifecycleState.Running)
    }

    @Test
    fun lateFailureFromOldGenerationCannotReplaceNewRunningState() = runBlocking {
        val coordinator = ServerLifecycleCoordinator(
            runtimeFactory = RecordingRuntimeFactory(),
            monotonicClock = MonotonicClock { 1_000 },
        )
        coordinator.start()
        coordinator.stop()
        coordinator.start()
        val actual = coordinator.state.value as ServerLifecycleState.Running

        coordinator.reportFailure(
            generation = actual.generation - 1,
            cause = ServerLifecycleError.NetworkLost,
        )

        assertEquals(actual, coordinator.state.value)
    }

    private class RecordingRuntimeFactory : ServerRuntimeFactory {
        val createCount = AtomicInteger()

        override fun create(): ServerRuntime {
            createCount.incrementAndGet()
            return object : ServerRuntime {
                override suspend fun start(): ServerEndpoint =
                    ServerEndpoint("192.168.1.24", 8787)

                override suspend fun stop() = Unit
            }
        }
    }
}
