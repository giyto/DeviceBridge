package ru.hznik.devicebridge.data.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.network.LanNetworkEvent
import ru.hznik.devicebridge.data.network.LanNetworkObserver
import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.model.ServerLifecycleState

class CoordinatorNetworkChangeTest {

    @Test
    fun lostNetworkStopsRuntimeWithoutAutomaticRestart() = runBlocking {
        val runtime = RecordingRuntime()
        val observer = FakeLanNetworkObserver()
        var createCalls = 0
        val coordinator = ServerLifecycleCoordinator(
            runtimeFactory = ServerRuntimeFactory {
                createCalls += 1
                runtime
            },
            monotonicClock = MonotonicClock { 10 },
            applicationScope = CoroutineScope(coroutineContext),
            lanNetworkObserver = observer,
        )
        coordinator.start()

        observer.emit(LanNetworkEvent.Lost)
        yield()

        assertEquals(1, runtime.stopCalls)
        assertEquals(1, observer.stopCalls)
        assertEquals(1, createCalls)
        assertEquals(
            ServerLifecycleState.Error(
                generation = 1,
                cause = ServerLifecycleError.NetworkLost,
            ),
            coordinator.state.value,
        )
    }

    @Test
    fun addressDisappearanceStopsTheOldEndpoint() = runBlocking {
        val fixture = fixture()

        fixture.observer.emit(
            LanNetworkEvent.LinkPropertiesChanged(
                interfaceName = "wlan0",
                addresses = setOf("192.168.1.25"),
            ),
        )
        yield()

        assertEquals(1, fixture.runtime.stopCalls)
        assertEquals(
            ServerLifecycleError.AddressChanged,
            (fixture.coordinator.state.value as ServerLifecycleState.Error).cause,
        )
    }

    @Test
    fun fingerprintChangeStopsEvenWhenAddressIsStillPresent() = runBlocking {
        val fixture = fixture()

        fixture.observer.emit(
            LanNetworkEvent.LinkPropertiesChanged(
                interfaceName = "ap0",
                addresses = setOf("192.168.1.24"),
            ),
        )
        yield()

        assertEquals(
            ServerLifecycleError.AddressChanged,
            (fixture.coordinator.state.value as ServerLifecycleState.Error).cause,
        )
    }

    @Test
    fun matchingNetworkKeepsCurrentRuntimeRunning() = runBlocking {
        val fixture = fixture()

        fixture.observer.emit(
            LanNetworkEvent.LinkPropertiesChanged(
                interfaceName = "wlan0",
                addresses = setOf("192.168.1.24"),
            ),
        )
        yield()

        assertTrue(fixture.coordinator.state.value is ServerLifecycleState.Running)
        assertEquals(0, fixture.runtime.stopCalls)
    }

    private suspend fun fixture(): Fixture {
        val runtime = RecordingRuntime()
        val observer = FakeLanNetworkObserver()
        val coordinator = ServerLifecycleCoordinator(
            runtimeFactory = ServerRuntimeFactory { runtime },
            monotonicClock = MonotonicClock { 10 },
            applicationScope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            lanNetworkObserver = observer,
        )
        coordinator.start()
        return Fixture(coordinator, runtime, observer)
    }

    private data class Fixture(
        val coordinator: ServerLifecycleCoordinator,
        val runtime: RecordingRuntime,
        val observer: FakeLanNetworkObserver,
    )

    private class FakeLanNetworkObserver : LanNetworkObserver {
        private var callback: ((LanNetworkEvent) -> Unit)? = null
        var stopCalls = 0

        override fun start(onEvent: (LanNetworkEvent) -> Unit) {
            callback = onEvent
        }

        override fun stop() {
            stopCalls += 1
            callback = null
        }

        fun emit(event: LanNetworkEvent) {
            callback?.invoke(event)
        }
    }

    private class RecordingRuntime : ServerRuntime {
        var stopCalls = 0

        override val networkFingerprint: String = "wlan0|192.168.1.24"

        override suspend fun start(): ServerEndpoint =
            ServerEndpoint("192.168.1.24", 8787)

        override suspend fun stop() {
            stopCalls += 1
        }
    }
}
