package ru.hznik.devicebridge.diagnostics.server

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedEmbeddedServerControllerTest {

    @Test
    fun initialStateIsStopped() {
        val controller = ManagedEmbeddedServerController(FakeRuntimeFactory())

        assertSame(ServerState.Stopped, controller.state.value)
    }

    @Test
    fun startPublishesStartingBeforeRunning() = runBlocking {
        val runtime = FakeRuntime()
        val factory = FakeRuntimeFactory(runtime)
        val controller = ManagedEmbeddedServerController(factory)
        val observedStates = mutableListOf<ServerState>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.state.collect(observedStates::add)
        }

        controller.start(preferredPort = 42_321)
        yield()
        collector.cancelAndJoin()

        assertEquals(1, factory.startCalls)
        assertTrue(observedStates.contains(ServerState.Starting))
        val running = controller.state.value as ServerState.Running
        assertEquals("0.0.0.0", running.address)
        assertEquals(42_321, running.port)
        assertTrue(running.token.isNotBlank())
    }

    @Test
    fun stopPublishesStoppingBeforeStopped() = runBlocking {
        val runtime = FakeRuntime()
        val controller = ManagedEmbeddedServerController(FakeRuntimeFactory(runtime))
        controller.start(preferredPort = 42_322)
        val observedStates = mutableListOf<ServerState>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.state.collect(observedStates::add)
        }

        controller.stop()
        yield()
        collector.cancelAndJoin()

        assertEquals(1, runtime.stopCalls)
        assertTrue(observedStates.contains(ServerState.Stopping))
        assertSame(ServerState.Stopped, controller.state.value)
    }

    @Test
    fun repeatedStartAndStopAreIdempotent() = runBlocking {
        val runtime = FakeRuntime()
        val factory = FakeRuntimeFactory(runtime)
        val controller = ManagedEmbeddedServerController(factory)

        controller.start()
        controller.start()
        controller.stop()
        controller.stop()

        assertEquals(1, factory.startCalls)
        assertEquals(1, runtime.stopCalls)
        assertSame(ServerState.Stopped, controller.state.value)
    }

    @Test
    fun concurrentStartAndStopFinishStoppedAcrossOneHundredRuns() = runBlocking {
        repeat(100) { iteration ->
            val factoryEntered = CompletableDeferred<Unit>()
            val allowStart = CompletableDeferred<Unit>()
            val runtime = FakeRuntime()
            val controller = ManagedEmbeddedServerController(
                DiagnosticServerRuntimeFactory { preferredPort, _ ->
                    factoryEntered.complete(Unit)
                    allowStart.await()
                    runtime.boundPort = preferredPort.takeIf { it > 0 } ?: 48_000
                    runtime
                },
            )

            val startJob = launch { controller.start() }
            factoryEntered.await()
            val stopJob = launch { controller.stop() }
            yield()
            allowStart.complete(Unit)
            startJob.join()
            stopJob.join()

            assertSame("Iteration $iteration", ServerState.Stopped, controller.state.value)
            assertEquals("Iteration $iteration", 1, runtime.stopCalls)
        }
    }

    @Test
    fun bindFailurePublishesErrorAndAllowsRetry() = runBlocking {
        val runtime = FakeRuntime()
        var attempts = 0
        val controller = ManagedEmbeddedServerController(
            DiagnosticServerRuntimeFactory { _, _ ->
                attempts += 1
                if (attempts == 1) {
                    throw java.net.BindException("Address already in use")
                }
                runtime
            },
        )

        val firstAttempt = runCatching { controller.start(preferredPort = 42_323) }

        assertTrue("Bind failure must be handled by controller", firstAttempt.isSuccess)
        val errorState = controller.state.value as ServerState.Error
        assertTrue(errorState.message.contains("Address already in use"))

        controller.start(preferredPort = 42_323)

        assertEquals(2, attempts)
        val running = controller.state.value as ServerState.Running
        assertEquals(runtime.address, running.address)
        assertEquals(runtime.port, running.port)
        assertTrue(running.token.isNotBlank())
    }

    @Test
    fun restartUsesNewTokenAndStopRemovesTokenFromState() = runBlocking {
        val tokens = ArrayDeque(listOf("first-token", "second-token"))
        val controller = ManagedEmbeddedServerController(
            runtimeFactory = FakeRuntimeFactory(),
            tokenGenerator = DiagnosticTokenGenerator { tokens.removeFirst() },
        )

        controller.start()
        val firstToken = (controller.state.value as ServerState.Running).token
        controller.stop()

        assertSame(ServerState.Stopped, controller.state.value)

        controller.start()
        val secondToken = (controller.state.value as ServerState.Running).token

        assertEquals("first-token", firstToken)
        assertEquals("second-token", secondToken)
        assertNotEquals(firstToken, secondToken)
    }

    private class FakeRuntimeFactory(
        private val runtime: FakeRuntime = FakeRuntime(),
    ) : DiagnosticServerRuntimeFactory {
        var startCalls: Int = 0
            private set

        override suspend fun start(
            preferredPort: Int,
            token: String,
        ): DiagnosticServerRuntime {
            startCalls += 1
            runtime.boundPort = preferredPort.takeIf { it > 0 } ?: 48_000
            yield()
            return runtime
        }
    }

    private class FakeRuntime : DiagnosticServerRuntime {
        override val address: String = "0.0.0.0"
        var boundPort: Int = 48_000
        var stopCalls: Int = 0
            private set

        override val port: Int
            get() = boundPort

        override suspend fun stop() {
            stopCalls += 1
            yield()
        }
    }
}
