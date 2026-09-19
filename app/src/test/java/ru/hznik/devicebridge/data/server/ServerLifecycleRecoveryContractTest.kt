package ru.hznik.devicebridge.data.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.network.LanNetworkEvent
import ru.hznik.devicebridge.data.network.LanNetworkObserver
import ru.hznik.devicebridge.domain.error.FailureCode
import ru.hznik.devicebridge.domain.error.toUserFacingFailure
import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.model.ServerLifecycleState

class ServerLifecycleRecoveryContractTest {

    @Test
    fun startupFailuresKeepTheirDistinctCodeAndReleasePartialRuntime() = runBlocking {
        val cases = listOf(
            ServerLifecycleError.LocalNetworkPermissionDenied to
                FailureCode.LOCAL_NETWORK_PERMISSION_DENIED,
            ServerLifecycleError.NoLanNetwork to FailureCode.NO_LAN_NETWORK,
            ServerLifecycleError.AmbiguousLanNetwork to FailureCode.AMBIGUOUS_LAN_NETWORK,
            ServerLifecycleError.ServerStartFailed to FailureCode.SERVER_START_FAILED,
        )

        cases.forEach { (source, expectedCode) ->
            val runtime = FailingRuntime(source)
            var createCalls = 0
            val coordinator = ServerLifecycleCoordinator(
                runtimeFactory = ServerRuntimeFactory {
                    createCalls += 1
                    runtime
                },
                monotonicClock = MonotonicClock { 10 },
            )

            coordinator.start()

            val error = coordinator.state.value as ServerLifecycleState.Error
            assertEquals(source, error.cause)
            assertEquals(expectedCode, error.cause.toUserFacingFailure().code)
            assertEquals(1, runtime.closeSessionCalls)
            assertEquals(1, runtime.stopCalls)
            assertEquals(1, createCalls)
        }
    }

    @Test
    fun delayedCallbackFromClosedGenerationCannotStopTheNewRuntime() = runBlocking {
        val observer = LeakyNetworkObserver()
        val runtimes = ArrayDeque(listOf(RecordingRuntime(), RecordingRuntime()))
        val coordinator = ServerLifecycleCoordinator(
            runtimeFactory = ServerRuntimeFactory { runtimes.removeFirst() },
            monotonicClock = MonotonicClock { 10 },
            applicationScope = CoroutineScope(Dispatchers.Unconfined),
            lanNetworkObserver = observer,
        )
        coordinator.start()
        coordinator.stop()
        coordinator.start()
        val running = coordinator.state.value as ServerLifecycleState.Running

        observer.emitFromStart(index = 0, event = LanNetworkEvent.Lost)
        yield()

        assertEquals(2, running.generation)
        assertEquals(running, coordinator.state.value)
        assertTrue(coordinator.state.value is ServerLifecycleState.Running)
    }

    private class FailingRuntime(
        private val error: ServerLifecycleError,
    ) : ServerRuntime {
        var closeSessionCalls = 0
        var stopCalls = 0

        override suspend fun start(): ServerEndpoint = throw ServerRuntimeStartException(error)
        override suspend fun closeSessionGeneration() { closeSessionCalls += 1 }
        override suspend fun stop() { stopCalls += 1 }
    }

    private class RecordingRuntime : ServerRuntime {
        override suspend fun start() = ServerEndpoint("192.168.1.24", 8787)
        override suspend fun stop() = Unit
    }

    private class LeakyNetworkObserver : LanNetworkObserver {
        private val callbacks = mutableListOf<(LanNetworkEvent) -> Unit>()

        override fun start(onEvent: (LanNetworkEvent) -> Unit) {
            callbacks += onEvent
        }

        override fun stop() = Unit

        fun emitFromStart(index: Int, event: LanNetworkEvent) {
            callbacks[index](event)
        }
    }
}
