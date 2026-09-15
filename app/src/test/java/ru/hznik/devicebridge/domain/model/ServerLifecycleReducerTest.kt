package ru.hznik.devicebridge.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ServerLifecycleReducerTest {

    private val endpoint = ServerEndpoint("192.168.1.24", 8787)

    @Test
    fun happyPathTraversesEveryLifecyclePhase() {
        val starting = ServerLifecycleReducer.reduce(
            ServerLifecycleState.Stopped,
            ServerLifecycleEvent.StartRequested(generation = 1),
        )
        assertEquals(ServerLifecycleState.Starting(1), starting)

        val running = ServerLifecycleReducer.reduce(
            starting,
            ServerLifecycleEvent.Started(
                generation = 1,
                endpoint = endpoint,
                startedAtElapsedRealtimeMs = 4_200,
            ),
        )
        assertEquals(
            ServerLifecycleState.Running(1, endpoint, 4_200),
            running,
        )

        val stopping = ServerLifecycleReducer.reduce(
            running,
            ServerLifecycleEvent.StopRequested(generation = 1),
        )
        assertEquals(ServerLifecycleState.Stopping(1), stopping)

        val stopped = ServerLifecycleReducer.reduce(
            stopping,
            ServerLifecycleEvent.Stopped(generation = 1),
        )
        assertSame(ServerLifecycleState.Stopped, stopped)
    }

    @Test
    fun startupAndRuntimeFailuresBecomeTypedErrorStates() {
        val startupError = ServerLifecycleReducer.reduce(
            ServerLifecycleState.Starting(2),
            ServerLifecycleEvent.Failed(
                generation = 2,
                cause = ServerLifecycleError.NoLanNetwork,
            ),
        )
        assertEquals(
            ServerLifecycleState.Error(2, ServerLifecycleError.NoLanNetwork),
            startupError,
        )

        val runtimeError = ServerLifecycleReducer.reduce(
            ServerLifecycleState.Running(3, endpoint, 100),
            ServerLifecycleEvent.Failed(
                generation = 3,
                cause = ServerLifecycleError.NetworkLost,
            ),
        )
        assertEquals(
            ServerLifecycleState.Error(3, ServerLifecycleError.NetworkLost),
            runtimeError,
        )
    }

    @Test
    fun invalidOrStaleEventsCannotReplaceActualState() {
        val running = ServerLifecycleState.Running(4, endpoint, 100)

        assertSame(
            running,
            ServerLifecycleReducer.reduce(
                running,
                ServerLifecycleEvent.Started(3, endpoint, 50),
            ),
        )
        assertSame(
            ServerLifecycleState.Stopped,
            ServerLifecycleReducer.reduce(
                ServerLifecycleState.Stopped,
                ServerLifecycleEvent.Started(4, endpoint, 100),
            ),
        )
    }

    @Test
    fun explicitRetryIsAllowedOnlyFromStoppedOrError() {
        val error = ServerLifecycleState.Error(
            generation = 5,
            cause = ServerLifecycleError.ServerStartFailed,
        )

        assertEquals(
            ServerLifecycleState.Starting(6),
            ServerLifecycleReducer.reduce(
                error,
                ServerLifecycleEvent.StartRequested(6),
            ),
        )

        val running = ServerLifecycleState.Running(7, endpoint, 100)
        assertSame(
            running,
            ServerLifecycleReducer.reduce(
                running,
                ServerLifecycleEvent.StartRequested(8),
            ),
        )
    }
}
