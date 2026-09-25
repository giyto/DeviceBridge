package ru.hznik.devicebridge.data.server

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.hznik.devicebridge.domain.model.LocalNameStatus
import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleEvent
import ru.hznik.devicebridge.domain.model.ServerLifecycleReducer
import ru.hznik.devicebridge.domain.model.ServerLifecycleState

class ServerLifecycleEndpointChangesTest {

    private val named = ServerEndpoint(
        host = "192.168.1.24",
        port = 8787,
        localName = "devicebridge.local",
        nameStatus = LocalNameStatus.Claimed("devicebridge", requestedTaken = false),
    )

    @Test
    fun lostNameReachesTheRunningState() = runBlocking {
        val runtime = ChangingRuntime(named)
        val coordinator = ServerLifecycleCoordinator(ServerRuntimeFactory { runtime }, MonotonicClock { 10 })
        coordinator.start()

        runtime.changes.value = named.withNameLost()

        withTimeout(2_000) {
            while ((coordinator.state.value as ServerLifecycleState.Running).endpoint.localName != null) delay(10)
        }
        val endpoint = (coordinator.state.value as ServerLifecycleState.Running).endpoint
        assertEquals("http://192.168.1.24:8787", endpoint.url)
        assertEquals(
            LocalNameStatus.Unavailable(LocalNameStatus.Reason.CONFLICT, "devicebridge"),
            endpoint.nameStatus,
        )
        coordinator.stop()
    }

    @Test
    fun staleEndpointChangeDoesNotTouchAnotherGeneration() {
        val running = ServerLifecycleState.Running(generation = 2, endpoint = named, startedAtElapsedRealtimeMs = 1)

        val stale = ServerLifecycleReducer.reduce(running, ServerLifecycleEvent.EndpointChanged(1, named.withNameLost()))
        val stopped = ServerLifecycleReducer.reduce(
            ServerLifecycleState.Stopped,
            ServerLifecycleEvent.EndpointChanged(2, named.withNameLost()),
        )

        assertEquals(running, stale)
        assertEquals(ServerLifecycleState.Stopped, stopped)
    }

    private class ChangingRuntime(initial: ServerEndpoint) : ServerRuntime {
        val changes = MutableStateFlow<ServerEndpoint?>(initial)

        override val endpointChanges: Flow<ServerEndpoint> = changes.filterNotNull()

        override suspend fun start(): ServerEndpoint = changes.value!!

        override suspend fun stop() = Unit
    }
}
