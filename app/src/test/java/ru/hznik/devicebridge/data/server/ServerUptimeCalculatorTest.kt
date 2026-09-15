package ru.hznik.devicebridge.data.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleState

class ServerUptimeCalculatorTest {

    @Test
    fun uptimeUsesMonotonicStartAndSurvivesConsumerRecreation() = runBlocking {
        val clock = MutableMonotonicClock(2_000)
        val coordinator = ServerLifecycleCoordinator(
            runtimeFactory = ServerRuntimeFactory { HealthyRuntime() },
            monotonicClock = clock,
        )
        coordinator.start()
        val processState = coordinator.state.value

        clock.now = 5_500
        val recreatedConsumer = ServerUptimeCalculator(clock)

        assertEquals(3_500L, recreatedConsumer.uptimeMs(processState))
    }

    @Test
    fun stoppedAndFutureTimestampsCannotProduceFakeUptime() {
        val clock = MutableMonotonicClock(1_000)
        val calculator = ServerUptimeCalculator(clock)

        assertNull(calculator.uptimeMs(ServerLifecycleState.Stopped))
        assertEquals(
            0L,
            calculator.uptimeMs(
                ServerLifecycleState.Running(
                    generation = 1,
                    endpoint = ServerEndpoint("192.168.1.24", 8787),
                    startedAtElapsedRealtimeMs = 2_000,
                ),
            ),
        )
    }

    private class MutableMonotonicClock(
        var now: Long,
    ) : MonotonicClock {
        override fun nowMs(): Long = now
    }

    private class HealthyRuntime : ServerRuntime {
        override suspend fun start(): ServerEndpoint =
            ServerEndpoint("192.168.1.24", 8787)

        override suspend fun stop() = Unit
    }
}
