package ru.hznik.devicebridge.data.server

import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.model.ServerStopReason

class ServerLifecycleCoordinatorCleanupTest {

    @Test
    fun partialStartupIsCleanedAndExplicitRetryCanSucceed() = runBlocking {
        val failedRuntime = RecordingRuntime(failOnStart = true)
        val healthyRuntime = RecordingRuntime()
        val factory = QueueRuntimeFactory(failedRuntime, healthyRuntime)
        val coordinator = ServerLifecycleCoordinator(factory, MonotonicClock { 10 })

        coordinator.start()

        assertEquals(1, failedRuntime.stopCalls)
        assertEquals(
            ServerLifecycleState.Error(
                generation = 1,
                cause = ServerLifecycleError.ServerStartFailed,
            ),
            coordinator.state.value,
        )

        coordinator.start()

        assertTrue(coordinator.state.value is ServerLifecycleState.Running)
        assertEquals(2, factory.createCalls)
    }

    @Test
    fun idleStopIsAPlainStopWhoseReasonClearsOnTheNextStart() = runBlocking {
        val factory = QueueRuntimeFactory(RecordingRuntime(), RecordingRuntime(port = 8_788))
        val coordinator = ServerLifecycleCoordinator(factory, MonotonicClock { 10 })

        coordinator.start()
        coordinator.stop(ServerStopReason.IdleTimeout(minutes = 30))

        assertEquals(ServerLifecycleState.Stopped, coordinator.state.value)
        assertEquals(ServerStopReason.IdleTimeout(minutes = 30), coordinator.lastStopReason.value)

        coordinator.start()
        assertNull(coordinator.lastStopReason.value)
        coordinator.stop()
        assertEquals(ServerStopReason.UserRequested, coordinator.lastStopReason.value)
    }

    @Test
    fun twentyStartStopCyclesReleaseEveryRuntimeExactlyOnce() = runBlocking {
        val runtimes = List(20) { RecordingRuntime(port = 8_000 + it) }
        val factory = QueueRuntimeFactory(*runtimes.toTypedArray())
        val coordinator = ServerLifecycleCoordinator(factory, MonotonicClock { 10 })

        repeat(20) {
            coordinator.start()
            coordinator.stop()
            coordinator.stop()
        }

        assertEquals(20, factory.createCalls)
        assertTrue(runtimes.all { it.stopCalls == 1 })
        assertEquals(ServerLifecycleState.Stopped, coordinator.state.value)
    }

    @Test
    fun stopTimeoutUsesTheSameCleanupPathAndAllowsExplicitRetry() = runBlocking {
        val stuckRuntime = object : ServerRuntime {
            override suspend fun start(): ServerEndpoint =
                ServerEndpoint("192.168.1.24", 8_787)

            override suspend fun stop() {
                awaitCancellation()
            }
        }
        val healthyRuntime = RecordingRuntime()
        var attempt = 0
        val coordinator = ServerLifecycleCoordinator(
            runtimeFactory = ServerRuntimeFactory {
                if (attempt++ == 0) stuckRuntime else healthyRuntime
            },
            monotonicClock = MonotonicClock { 10 },
            stopTimeoutPolicy = StopTimeoutPolicy(timeoutMillis = 25),
        )
        coordinator.start()

        coordinator.stop()

        assertEquals(
            ServerLifecycleError.StopTimedOut,
            (coordinator.state.value as ServerLifecycleState.Error).cause,
        )
        coordinator.start()
        assertTrue(coordinator.state.value is ServerLifecycleState.Running)
    }

    private class QueueRuntimeFactory(
        vararg runtimes: RecordingRuntime,
    ) : ServerRuntimeFactory {
        private val queue = ArrayDeque(runtimes.toList())
        var createCalls = 0

        override fun create(): ServerRuntime {
            createCalls += 1
            return queue.removeFirst()
        }
    }

    private class RecordingRuntime(
        private val failOnStart: Boolean = false,
        private val port: Int = 8_787,
    ) : ServerRuntime {
        var stopCalls = 0

        override suspend fun start(): ServerEndpoint {
            if (failOnStart) {
                error("bind failed")
            }
            return ServerEndpoint("192.168.1.24", port)
        }

        override suspend fun stop() {
            stopCalls += 1
        }
    }
}
