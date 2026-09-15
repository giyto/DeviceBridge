package ru.hznik.devicebridge.feature.home

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.hznik.devicebridge.data.permission.ServerPermissionGateway
import ru.hznik.devicebridge.data.permission.ServerPermissionPolicy
import ru.hznik.devicebridge.data.permission.ServerPermissionRequestPlanner
import ru.hznik.devicebridge.data.permission.ServerPermissionSnapshot
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.model.ServerStopReason
import ru.hznik.devicebridge.domain.repository.ServerLifecycleRepository
import ru.hznik.devicebridge.domain.usecase.ObserveServerLifecycleUseCase
import ru.hznik.devicebridge.domain.usecase.StartServerUseCase
import ru.hznik.devicebridge.domain.usecase.StopServerUseCase

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun lifecycleStateAndLiveUptimeComeFromRepository() = runTest(dispatcher) {
        val repository = FakeRepository()
        val clock = FakeClock(15_000)
        val ticker = FakeTicker()
        val viewModel = createViewModel(repository, clock = clock, ticker = ticker)

        repository.mutableState.value = ServerLifecycleState.Running(
            generation = 4,
            endpoint = ServerEndpoint("192.168.1.24", 49_321),
            startedAtElapsedRealtimeMs = 10_000,
        )
        runCurrent()

        assertEquals(HomeServerStatus.Running, viewModel.uiState.value.status)
        assertEquals("http://192.168.1.24:49321", viewModel.uiState.value.localAddress)
        assertEquals(5L, viewModel.uiState.value.uptimeSeconds)

        clock.nowMs = 17_000
        ticker.pulse()
        runCurrent()
        assertEquals(7L, viewModel.uiState.value.uptimeSeconds)
    }

    @Test
    fun api37RequestsLanPermissionOnceBeforeStart() = runTest(dispatcher) {
        val repository = FakeRepository()
        val gateway = FakePermissionGateway(snapshot(sdk = 37, lan = false))
        val viewModel = createViewModel(repository, gateway)
        val effect = async { viewModel.effects.first() }
        runCurrent()

        viewModel.onAction(HomeAction.StartClicked)
        runCurrent()

        assertEquals(
            HomeEffect.RequestPermissions(
                listOf("android.permission.ACCESS_LOCAL_NETWORK"),
            ),
            effect.await(),
        )
        assertEquals(0, repository.startCalls)
    }

    @Test
    fun api29StartsWithoutLanRequestAndStopIsIdempotent() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = createViewModel(
            repository,
            FakePermissionGateway(snapshot(sdk = 29, lan = false)),
        )

        viewModel.onAction(HomeAction.StartClicked)
        runCurrent()
        assertEquals(1, repository.startCalls)

        repository.mutableState.value = runningState()
        runCurrent()
        viewModel.onAction(HomeAction.StopClicked)
        viewModel.onAction(HomeAction.StopClicked)
        runCurrent()
        assertEquals(listOf(ServerStopReason.UserRequested), repository.stopReasons)
    }

    @Test
    fun deniedLanShowsRetryWhileNotificationDenialDoesNotBlockStart() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val gateway = FakePermissionGateway(snapshot(sdk = 37, lan = false))
            val viewModel = createViewModel(repository, gateway)

            viewModel.onAction(HomeAction.PermissionsResolved(localNetworkCanAskAgain = true))
            runCurrent()
            assertEquals(0, repository.startCalls)
            assertTrue(viewModel.uiState.value.isPermissionExplanationVisible)

            gateway.current = snapshot(sdk = 37, lan = true, notifications = false)
            viewModel.onAction(HomeAction.PermissionsResolved(localNetworkCanAskAgain = true))
            runCurrent()
            assertEquals(1, repository.startCalls)
            assertTrue(viewModel.uiState.value.showNotificationWarning)
        }

    @Test
    fun errorHasNoStaleEndpointAndViewModelHasNoPlatformServerImports() =
        runTest(dispatcher) {
            val repository = FakeRepository(
                ServerLifecycleState.Error(7, ServerLifecycleError.NetworkLost),
            )
            val viewModel = createViewModel(repository)
            runCurrent()

            assertEquals(HomeServerStatus.Error, viewModel.uiState.value.status)
            assertNull(viewModel.uiState.value.localAddress)
            assertFalse(viewModel.uiState.value.canSendText)
            assertFalse(viewModel.uiState.value.canSendFiles)

            val source = File(
                "src/main/java/ru/hznik/devicebridge/feature/home/HomeViewModel.kt",
            ).readText()
            assertFalse(source.contains("io.ktor"))
            assertFalse(source.contains("ServerForegroundService"))
            assertFalse(source.contains("android.app.Service"))
        }

    private fun createViewModel(
        repository: FakeRepository,
        gateway: FakePermissionGateway = FakePermissionGateway(snapshot()),
        clock: FakeClock = FakeClock(0),
        ticker: FakeTicker = FakeTicker(),
    ) = HomeViewModel(
        StartServerUseCase(repository),
        StopServerUseCase(repository),
        ObserveServerLifecycleUseCase(repository),
        gateway,
        ServerPermissionRequestPlanner(ServerPermissionPolicy()),
        clock,
        ticker,
    )

    private class FakeRepository(
        initial: ServerLifecycleState = ServerLifecycleState.Stopped,
    ) : ServerLifecycleRepository {
        val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<ServerLifecycleState> = mutableState
        var startCalls = 0
        val stopReasons = mutableListOf<ServerStopReason>()

        override suspend fun start() { startCalls += 1 }
        override suspend fun stop(reason: ServerStopReason) { stopReasons += reason }
    }

    private class FakePermissionGateway(
        var current: ServerPermissionSnapshot,
    ) : ServerPermissionGateway {
        override fun snapshot(localNetworkCanAskAgain: Boolean) =
            current.copy(localNetworkCanAskAgain = localNetworkCanAskAgain)
    }

    private class FakeClock(var nowMs: Long) : MonotonicClock {
        override fun nowMs() = nowMs
    }

    private class FakeTicker : HomeUptimeTicker {
        private val pulses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override fun ticks() = pulses
        fun pulse() { pulses.tryEmit(Unit) }
    }

    companion object {
        fun snapshot(
            sdk: Int = 29,
            lan: Boolean = true,
            notifications: Boolean = true,
        ) = ServerPermissionSnapshot(sdk, lan, notifications)

        fun runningState() = ServerLifecycleState.Running(
            generation = 1,
            endpoint = ServerEndpoint("192.168.1.24", 8_787),
            startedAtElapsedRealtimeMs = 0,
        )
    }
}
