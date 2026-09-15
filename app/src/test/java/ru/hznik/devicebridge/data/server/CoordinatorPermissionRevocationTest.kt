package ru.hznik.devicebridge.data.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.permission.PermissionRevocationObserver
import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.model.ServerLifecycleState

class CoordinatorPermissionRevocationTest {

    @Test
    fun permissionRevocationClosesRuntimeAndRequiresExplicitRestart() = runBlocking {
        val runtime = RecordingRuntime()
        val observer = FakePermissionRevocationObserver()
        val coordinator = ServerLifecycleCoordinator(
            runtimeFactory = ServerRuntimeFactory { runtime },
            monotonicClock = MonotonicClock { 10 },
            applicationScope = CoroutineScope(coroutineContext),
            permissionRevocationObserver = observer,
        )
        coordinator.start()

        observer.revoke()
        yield()

        assertEquals(1, runtime.stopCalls)
        assertEquals(
            ServerLifecycleState.Error(
                generation = 1,
                cause = ServerLifecycleError.PermissionRevoked,
            ),
            coordinator.state.value,
        )

        coordinator.start()
        assertTrue(coordinator.state.value is ServerLifecycleState.Running)
        assertEquals(2, observer.startCalls)
    }

    private class FakePermissionRevocationObserver : PermissionRevocationObserver {
        private var callback: (() -> Unit)? = null
        var startCalls = 0

        override fun start(onRevoked: () -> Unit) {
            startCalls += 1
            callback = onRevoked
        }

        override fun stop() {
            callback = null
        }

        fun revoke() {
            callback?.invoke()
        }
    }

    private class RecordingRuntime : ServerRuntime {
        var stopCalls = 0

        override suspend fun start(): ServerEndpoint =
            ServerEndpoint("192.168.1.24", 8787)

        override suspend fun stop() {
            stopCalls += 1
        }
    }
}
