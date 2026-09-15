package ru.hznik.devicebridge.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.model.ServerStopReason
import ru.hznik.devicebridge.domain.repository.ServerLifecycleRepository

class ServerLifecycleUseCasesTest {

    @Test
    fun observeReturnsRepositoryStateWithoutCreatingAnotherSourceOfTruth() {
        val repository = FakeServerLifecycleRepository()

        val observed = ObserveServerLifecycleUseCase(repository)()

        assertSame(repository.state, observed)
    }

    @Test
    fun startAndStopDelegateTypedCommandsToRepository() = runBlocking {
        val repository = FakeServerLifecycleRepository()

        StartServerUseCase(repository)()
        StopServerUseCase(repository)(ServerStopReason.AddressChanged)

        assertEquals(1, repository.startCalls)
        assertEquals(listOf(ServerStopReason.AddressChanged), repository.stopReasons)
    }

    private class FakeServerLifecycleRepository : ServerLifecycleRepository {
        override val state: StateFlow<ServerLifecycleState> =
            MutableStateFlow(ServerLifecycleState.Stopped)
        var startCalls: Int = 0
        val stopReasons = mutableListOf<ServerStopReason>()

        override suspend fun start() {
            startCalls += 1
        }

        override suspend fun stop(reason: ServerStopReason) {
            stopReasons += reason
        }
    }
}
