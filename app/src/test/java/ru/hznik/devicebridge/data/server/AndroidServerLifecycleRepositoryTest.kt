package ru.hznik.devicebridge.data.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.model.ServerStopReason

class AndroidServerLifecycleRepositoryTest {
    @Test
    fun stateIsCoordinatorStateAndCommandsGoToForegroundService() = runBlocking {
        val state = MutableStateFlow<ServerLifecycleState>(ServerLifecycleState.Stopped)
        val commands = FakeCommands()
        val repository = AndroidServerLifecycleRepository(state, commands)

        repository.start()
        repository.stop(ServerStopReason.UserRequested)

        assertSame(state, repository.state)
        assertEquals(1, commands.startCalls)
        assertEquals(1, commands.stopCalls)
    }

    private class FakeCommands : ServerServiceCommandGateway {
        var startCalls = 0
        var stopCalls = 0

        override fun requestStart() { startCalls += 1 }
        override fun requestStop() { stopCalls += 1 }
    }
}
