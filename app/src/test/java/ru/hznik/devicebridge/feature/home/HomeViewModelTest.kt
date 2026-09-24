package ru.hznik.devicebridge.feature.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
import ru.hznik.devicebridge.server.ServerStartRequests
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
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository
import ru.hznik.devicebridge.domain.repository.TextTransferRepository
import ru.hznik.devicebridge.domain.repository.FileTransferRepository
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.file.FileTransferState
import ru.hznik.devicebridge.domain.usecase.ObserveFileTransfersUseCase
import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingChallengeId
import ru.hznik.devicebridge.domain.session.PairingCodeState
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.session.PendingBrowserRequest
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.usecase.ApproveBrowserRequestUseCase
import ru.hznik.devicebridge.domain.usecase.DenyBrowserRequestUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveBrowserSessionsUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveServerLifecycleUseCase
import ru.hznik.devicebridge.domain.usecase.RevokeBrowserSessionUseCase
import ru.hznik.devicebridge.domain.usecase.StartServerUseCase
import ru.hznik.devicebridge.domain.usecase.StopServerUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveTextTransfersUseCase
import ru.hznik.devicebridge.domain.text.IncomingTextRequest
import ru.hznik.devicebridge.domain.text.SendTextRequest
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferFailureReason
import ru.hznik.devicebridge.domain.text.TextTransferItem
import ru.hznik.devicebridge.domain.text.TextTransferResult
import ru.hznik.devicebridge.domain.text.TextTransferState
import ru.hznik.devicebridge.domain.text.TextTransferStatus

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
    fun runningStateIncludesPairingCountdownPendingRequestsAndSessions() =
        runTest(dispatcher) {
            val lifecycle = FakeRepository(runningState())
            val sessions = FakeBrowserSessionRepository(activeBrowserState())
            val clock = FakeClock(12_000)
            val ticker = FakeTicker()
            val viewModel = createViewModel(
                repository = lifecycle,
                sessionRepository = sessions,
                clock = clock,
                ticker = ticker,
            )
            runCurrent()

            val initial = viewModel.uiState.value
            assertEquals("123456", initial.pairingCode)
            assertEquals(8L, initial.pairingExpiresInSeconds)
            assertEquals(
                listOf(PairingRequestId("request-1"), PairingRequestId("request-2")),
                initial.pendingBrowsers.map { it.id },
            )
            assertEquals(listOf(BrowserSessionId("session-1")), initial.activeBrowsers.map { it.id })
            assertEquals("Edge", initial.pendingBrowsers.first().browserLabel)
            assertTrue(initial.pendingBrowsers.first().rememberBrowserRequested)
            assertFalse(initial.pendingBrowsers.last().rememberBrowserRequested)
            assertEquals("192.168.1.3", initial.activeBrowsers.single().sourceIpv4)

            clock.nowMs = 15_100
            ticker.pulse()
            runCurrent()
            assertEquals(5L, viewModel.uiState.value.pairingExpiresInSeconds)
        }

    @Test
    fun closedBrowserTabStaysListedOfflineAndReconnectFlipsItBack() =
        runTest(dispatcher) {
            val chrome = BrowserSessionId("session-1")
            val edge = BrowserSessionId("session-2")
            val base = activeBrowserState()
            val sessions = FakeBrowserSessionRepository(
                BrowserSessionState.active(
                    generationId = requireNotNull(base.generationId),
                    pairingCode = requireNotNull(base.pairingCode),
                    sessions = base.sessions + BrowserSession(
                        id = edge,
                        generationId = ServerGenerationId(1),
                        browserLabel = "Edge",
                        sourceIpv4 = "192.168.1.5",
                        connectedAtElapsedRealtimeMs = 11_500,
                    ),
                ),
            )
            val viewModel = createViewModel(
                repository = FakeRepository(runningState()),
                sessionRepository = sessions,
            )
            runCurrent()
            assertEquals(listOf(chrome, edge), viewModel.uiState.value.activeBrowsers.map { it.id })
            assertEquals(2, viewModel.uiState.value.connectedBrowserCount)

            // Chrome's tab closed its socket: the session survives and is listed as offline,
            // after the connected browser, but it no longer counts as connected.
            sessions.mutableConnectedSessionIds.value = setOf(edge)
            runCurrent()
            val offline = viewModel.uiState.value
            assertEquals(
                listOf(edge to true, chrome to false),
                offline.activeBrowsers.map { it.id to it.connected },
            )
            assertEquals(1, offline.connectedBrowserCount)
            assertTrue(offline.canSendText)

            // No live browser at all: nothing can be sent, but both sessions stay revocable.
            sessions.mutableConnectedSessionIds.value = emptySet()
            runCurrent()
            val allOffline = viewModel.uiState.value
            assertEquals(2, allOffline.activeBrowsers.size)
            assertTrue(allOffline.activeBrowsers.none { it.connected })
            assertEquals(0, allOffline.connectedBrowserCount)
            assertFalse(allOffline.canSendText)
            assertFalse(allOffline.canSendFiles)
            assertEquals("123456", allOffline.pairingCode)

            viewModel.onAction(HomeAction.RevokeBrowser(chrome))
            runCurrent()
            assertEquals(listOf(chrome), sessions.revoked)

            // Reloading the tab reconnects the same session.
            sessions.mutableConnectedSessionIds.value = setOf(chrome, edge)
            runCurrent()
            assertEquals(
                listOf(chrome to true, edge to true),
                viewModel.uiState.value.activeBrowsers.map { it.id to it.connected },
            )
            assertEquals(2, viewModel.uiState.value.connectedBrowserCount)
        }

    @Test
    fun runningStateIncludesOnlyActiveFileTransfersWithoutSensitiveMetadata() =
        runTest(dispatcher) {
            val files = FakeFileTransferRepository()
            val viewModel = createViewModel(
                repository = FakeRepository(runningState()),
                sessionRepository = FakeBrowserSessionRepository(activeBrowserState()),
                fileRepository = files,
            )
            files.mutableState.value = FileTransferSnapshot(
                listOf(
                    FileTransferState.queued(
                        generationId = ServerGenerationId(1),
                        ownerSessionId = BrowserSessionId("session-1"),
                        metadata = FileTransferMetadata(
                            id = FileTransferId("home-file"),
                            displayName = "report.pdf",
                            sizeBytes = 100,
                            mimeType = "application/pdf",
                            sha256 = "a".repeat(64),
                            direction = FileTransferDirection.BROWSER_TO_ANDROID,
                        ),
                    ),
                ),
            )
            runCurrent()

            val transfer = viewModel.uiState.value.activeFileTransfers.single()
            assertEquals("report.pdf", transfer.displayName)
            assertEquals(100, transfer.sizeBytes)
            assertEquals(FileTransferDirection.BROWSER_TO_ANDROID, transfer.direction)

            files.mutableState.value = FileTransferSnapshot(
                files.mutableState.value.items.map { item ->
                    item.evolve(
                        phase = FileTransferPhase.TRANSFERRING,
                        bytesTransferred = 60,
                        resumedFromBytes = 40,
                    )
                },
            )
            runCurrent()
            assertEquals(40L, viewModel.uiState.value.activeFileTransfers.single().resumedFromBytes)
        }

    @Test
    fun approveDenyAndRevokeDelegateExactTypedIdOnlyOnceWhileInFlight() =
        runTest(dispatcher) {
            val sessions = FakeBrowserSessionRepository(activeBrowserState())
            val viewModel = createViewModel(
                repository = FakeRepository(runningState()),
                sessionRepository = sessions,
            )
            runCurrent()

            val requestId = PairingRequestId("request-1")
            val deniedRequestId = PairingRequestId("request-2")
            val sessionId = BrowserSessionId("session-1")
            viewModel.onAction(HomeAction.ApproveAndRememberBrowser(requestId))
            viewModel.onAction(HomeAction.ApproveAndRememberBrowser(requestId))
            viewModel.onAction(HomeAction.DenyBrowser(deniedRequestId))
            viewModel.onAction(HomeAction.DenyBrowser(deniedRequestId))
            viewModel.onAction(HomeAction.RevokeBrowser(sessionId))
            viewModel.onAction(HomeAction.RevokeBrowser(sessionId))
            runCurrent()

            assertEquals(listOf(requestId), sessions.approvedAndRemembered)
            assertEquals(listOf(deniedRequestId), sessions.denied)
            assertEquals(listOf(sessionId), sessions.revoked)
        }

    @Test
    fun stoppedLifecycleHidesStaleSessionDataAndRecreationReadsLiveProcessState() =
        runTest(dispatcher) {
            val lifecycle = FakeRepository(runningState())
            val sessions = FakeBrowserSessionRepository(activeBrowserState())
            val first = createViewModel(lifecycle, sessionRepository = sessions)
            runCurrent()
            assertEquals("123456", first.uiState.value.pairingCode)

            val recreated = createViewModel(lifecycle, sessionRepository = sessions)
            runCurrent()
            assertEquals(first.uiState.value.pairingCode, recreated.uiState.value.pairingCode)
            assertEquals(first.uiState.value.activeBrowsers, recreated.uiState.value.activeBrowsers)

            lifecycle.mutableState.value = ServerLifecycleState.Stopped
            runCurrent()
            assertNull(first.uiState.value.pairingCode)
            assertTrue(first.uiState.value.pendingBrowsers.isEmpty())
            assertTrue(first.uiState.value.activeBrowsers.isEmpty())
        }

    @Test
    fun idleStopReasonIsShownOnlyForAnAutomaticStop() = runTest(dispatcher) {
        val lifecycle = FakeRepository(runningState())
        val viewModel = createViewModel(lifecycle)
        runCurrent()

        lifecycle.mutableLastStopReason.value = ServerStopReason.IdleTimeout(minutes = 30)
        lifecycle.mutableState.value = ServerLifecycleState.Stopped
        runCurrent()
        assertEquals(HomeServerStatus.Stopped, viewModel.uiState.value.status)
        assertEquals(30, viewModel.uiState.value.idleStoppedAfterMinutes)
        assertTrue(viewModel.uiState.value.canStart)

        lifecycle.mutableLastStopReason.value = null
        lifecycle.mutableState.value = runningState()
        runCurrent()
        lifecycle.mutableLastStopReason.value = ServerStopReason.UserRequested
        lifecycle.mutableState.value = ServerLifecycleState.Stopped
        runCurrent()
        assertNull(viewModel.uiState.value.idleStoppedAfterMinutes)
    }

    @Test
    fun currentTextStatusTracksActiveCompletedFailedAndClearsOutsideRunning() =
        runTest(dispatcher) {
            val lifecycle = FakeRepository(runningState())
            val textRepository = FakeTextTransferRepository()
            val viewModel = createViewModel(
                repository = lifecycle,
                textRepository = textRepository,
            )
            runCurrent()
            assertEquals(HomeTextTransferStatus.Idle, viewModel.uiState.value.textTransferStatus)

            val pending = outgoingTextItem()
            textRepository.mutableState.value = TextTransferState.of(listOf(pending))
            runCurrent()
            assertEquals(HomeTextTransferStatus.Active, viewModel.uiState.value.textTransferStatus)

            val sending = pending.transitionTo(TextTransferStatus.SENDING, 1_001)
            val delivered = sending.transitionTo(TextTransferStatus.DELIVERED, 1_002)
            textRepository.mutableState.value = TextTransferState.of(listOf(delivered))
            runCurrent()
            assertEquals(
                HomeTextTransferStatus.Completed,
                viewModel.uiState.value.textTransferStatus,
            )

            val failed = sending.transitionTo(
                next = TextTransferStatus.FAILED,
                changedAtEpochMillis = 1_002,
                failureReason = TextTransferFailureReason.CONNECTION_LOST,
            )
            textRepository.mutableState.value = TextTransferState.of(listOf(failed))
            runCurrent()
            assertEquals(HomeTextTransferStatus.Failed, viewModel.uiState.value.textTransferStatus)

            lifecycle.mutableState.value = ServerLifecycleState.Stopped
            runCurrent()
            assertEquals(HomeTextTransferStatus.Idle, viewModel.uiState.value.textTransferStatus)
        }

    @Test
    fun tileStartRequestRunsTheNormalStartOnce() = runTest(dispatcher) {
        val repository = FakeRepository()
        val requests = ServerStartRequests()
        val viewModel = createViewModel(repository, startRequests = requests)
        runCurrent()

        requests.request()
        runCurrent()
        assertEquals(1, repository.startCalls)
        assertNull(requests.pending.value)

        // A recreated screen must not start the server a second time.
        createViewModel(repository, startRequests = requests)
        runCurrent()
        assertEquals(1, repository.startCalls)
        assertTrue(viewModel.uiState.value.commandPending)
    }

    @Test
    fun tileStartRequestAsksForMissingLanPermissionFirst() = runTest(dispatcher) {
        val repository = FakeRepository()
        val requests = ServerStartRequests()
        val viewModel = createViewModel(
            repository,
            FakePermissionGateway(snapshot(sdk = 37, lan = false)),
            startRequests = requests,
        )
        val effect = async { viewModel.effects.first() }
        runCurrent()

        requests.request()
        runCurrent()

        assertEquals(
            HomeEffect.RequestPermissions(listOf("android.permission.ACCESS_LOCAL_NETWORK")),
            effect.await(),
        )
        assertEquals(0, repository.startCalls)
    }

    @Test
    fun consumedPermissionEffectDoesNotReplayOrStartServer() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = createViewModel(
            repository,
            FakePermissionGateway(snapshot(sdk = 37, lan = false)),
        )
        val firstEffect = async { viewModel.effects.first() }
        runCurrent()

        viewModel.onAction(HomeAction.StartClicked)

        assertEquals(
            HomeEffect.RequestPermissions(
                listOf("android.permission.ACCESS_LOCAL_NETWORK"),
            ),
            firstEffect.await(),
        )
        assertNull(withTimeoutOrNull(100) { viewModel.effects.first() })
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
        }

    @Test
    fun lifecycleFailuresExposeDistinctFailureCodeAndRecoveryAction() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val viewModel = createViewModel(repository)
            val cases = listOf(
                Triple(
                    ServerLifecycleError.LocalNetworkPermissionDenied,
                    ru.hznik.devicebridge.domain.error.FailureCode.LOCAL_NETWORK_PERMISSION_DENIED,
                    ru.hznik.devicebridge.domain.error.RecoveryAction.REQUEST_PERMISSION,
                ),
                Triple(
                    ServerLifecycleError.PermissionRevoked,
                    ru.hznik.devicebridge.domain.error.FailureCode.LOCAL_NETWORK_PERMISSION_REVOKED,
                    ru.hznik.devicebridge.domain.error.RecoveryAction.OPEN_SETTINGS,
                ),
                Triple(
                    ServerLifecycleError.NetworkLost,
                    ru.hznik.devicebridge.domain.error.FailureCode.NETWORK_LOST,
                    ru.hznik.devicebridge.domain.error.RecoveryAction.CONNECT_TO_LOCAL_NETWORK,
                ),
            )

            cases.forEachIndexed { index, (cause, code, action) ->
                repository.mutableState.value = ServerLifecycleState.Error(index + 1L, cause)
                runCurrent()
                assertEquals(code, viewModel.uiState.value.failure?.code)
                assertTrue(action in viewModel.uiState.value.failure?.recoveryActions.orEmpty())
                assertNull(viewModel.uiState.value.localAddress)
            }
        }

    @Test
    fun explicitRecoveryIntentsRequestPermissionOpenSettingsOrStartAgain() =
        runTest(dispatcher) {
            val repository = FakeRepository(
                ServerLifecycleState.Error(
                    1,
                    ServerLifecycleError.LocalNetworkPermissionDenied,
                ),
            )
            val gateway = FakePermissionGateway(snapshot(sdk = 37, lan = false))
            val viewModel = createViewModel(repository, gateway)
            val permissionEffect = async { viewModel.effects.first() }
            runCurrent()

            viewModel.onAction(HomeAction.RequestPermissionClicked)
            runCurrent()
            assertEquals(
                HomeEffect.RequestPermissions(
                    listOf("android.permission.ACCESS_LOCAL_NETWORK"),
                ),
                permissionEffect.await(),
            )
            assertEquals(0, repository.startCalls)

            val settingsEffect = async { viewModel.effects.first() }
            viewModel.onAction(HomeAction.OpenSettingsClicked)
            runCurrent()
            assertEquals(HomeEffect.OpenAppSettings, settingsEffect.await())
            assertEquals(0, repository.startCalls)

            gateway.current = snapshot(sdk = 29, lan = true)
            viewModel.onAction(HomeAction.StartAgainClicked)
            runCurrent()
            assertEquals(1, repository.startCalls)
        }

    private fun createViewModel(
        repository: FakeRepository,
        gateway: FakePermissionGateway = FakePermissionGateway(snapshot()),
        clock: FakeClock = FakeClock(0),
        ticker: FakeTicker = FakeTicker(),
        sessionRepository: FakeBrowserSessionRepository = FakeBrowserSessionRepository(),
        textRepository: FakeTextTransferRepository = FakeTextTransferRepository(),
        fileRepository: FakeFileTransferRepository = FakeFileTransferRepository(),
        startRequests: ServerStartRequests = ServerStartRequests(),
    ) = HomeViewModel(
        StartServerUseCase(repository),
        StopServerUseCase(repository),
        ObserveServerLifecycleUseCase(repository),
        gateway,
        ServerPermissionRequestPlanner(ServerPermissionPolicy()),
        clock,
        ticker,
        ObserveBrowserSessionsUseCase(sessionRepository),
        ApproveBrowserRequestUseCase(sessionRepository),
        DenyBrowserRequestUseCase(sessionRepository),
        RevokeBrowserSessionUseCase(sessionRepository),
        ObserveTextTransfersUseCase(textRepository),
        ObserveFileTransfersUseCase(fileRepository),
        serverStartRequests = startRequests,
    )

    private class FakeRepository(
        initial: ServerLifecycleState = ServerLifecycleState.Stopped,
    ) : ServerLifecycleRepository {
        val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<ServerLifecycleState> = mutableState
        val mutableLastStopReason = MutableStateFlow<ServerStopReason?>(null)
        override val lastStopReason: StateFlow<ServerStopReason?> = mutableLastStopReason
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

    private class FakeBrowserSessionRepository(
        initial: BrowserSessionState = BrowserSessionState.inactive(),
    ) : BrowserSessionRepository {
        val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<BrowserSessionState> = mutableState
        val mutableConnectedSessionIds = MutableStateFlow(initial.sessions.map { it.id }.toSet())
        override val connectedSessionIds: StateFlow<Set<BrowserSessionId>> =
            mutableConnectedSessionIds
        val approvedAndRemembered = mutableListOf<PairingRequestId>()
        val denied = mutableListOf<PairingRequestId>()
        val revoked = mutableListOf<BrowserSessionId>()

        override suspend fun approve(requestId: PairingRequestId) = Unit

        override suspend fun approveAndRemember(requestId: PairingRequestId) {
            approvedAndRemembered += requestId
        }

        override suspend fun deny(requestId: PairingRequestId) {
            denied += requestId
        }

        override suspend fun revoke(sessionId: BrowserSessionId) {
            revoked += sessionId
        }
    }

    private class FakeTextTransferRepository : TextTransferRepository {
        val mutableState = MutableStateFlow(TextTransferState.empty())
        override val state: StateFlow<TextTransferState> = mutableState

        override suspend fun send(request: SendTextRequest): TextTransferResult =
            error("Not used")

        override suspend fun receive(request: IncomingTextRequest): TextTransferResult =
            error("Not used")

        override suspend fun retry(messageId: TextMessageId): TextTransferResult =
            error("Not used")
    }

    private class FakeFileTransferRepository : FileTransferRepository {
        val mutableState = MutableStateFlow(FileTransferSnapshot(emptyList()))
        override val state: StateFlow<FileTransferSnapshot> = mutableState
        override suspend fun create(request: ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest) =
            FileTransferOperationResult.InvalidState
        override suspend fun approve(transferId: FileTransferId, destinationId: ru.hznik.devicebridge.domain.file.FileDestinationId?) =
            FileTransferOperationResult.InvalidState
        override suspend fun cancel(transferId: FileTransferId) = FileTransferOperationResult.InvalidState
        override suspend fun retry(transferId: FileTransferId) = FileTransferOperationResult.InvalidState
        override suspend fun verify(request: ru.hznik.devicebridge.domain.file.VerifyFileTransferRequest) =
            FileTransferOperationResult.InvalidState
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

        fun activeBrowserState() = BrowserSessionState.active(
            generationId = ServerGenerationId(1),
            pairingCode = PairingCodeState(
                value = "123456",
                expiresAtElapsedRealtimeMs = 20_000,
            ),
            pendingRequests = listOf(
                PendingBrowserRequest(
                    id = PairingRequestId("request-1"),
                    challengeId = PairingChallengeId("challenge-1"),
                    generationId = ServerGenerationId(1),
                    browserLabel = "Edge",
                    sourceIpv4 = "192.168.1.2",
                    createdAtElapsedRealtimeMs = 10_000,
                    expiresAtElapsedRealtimeMs = 18_000,
                    rememberBrowserRequested = true,
                ),
                PendingBrowserRequest(
                    id = PairingRequestId("request-2"),
                    challengeId = PairingChallengeId("challenge-2"),
                    generationId = ServerGenerationId(1),
                    browserLabel = "Firefox",
                    sourceIpv4 = "192.168.1.4",
                    createdAtElapsedRealtimeMs = 10_500,
                    expiresAtElapsedRealtimeMs = 18_500,
                ),
            ),
            sessions = listOf(
                BrowserSession(
                    id = BrowserSessionId("session-1"),
                    generationId = ServerGenerationId(1),
                    browserLabel = "Chrome",
                    sourceIpv4 = "192.168.1.3",
                    connectedAtElapsedRealtimeMs = 11_000,
                ),
            ),
        )

        fun outgoingTextItem() = TextTransferItem.outgoing(
            id = TextMessageId("home-status"),
            generationId = ServerGenerationId(1),
            sessionId = BrowserSessionId("session-1"),
            browserLabel = "Chrome",
            content = "секретное содержимое",
            contentKind = TextContentKind.TEXT,
            createdAtEpochMillis = 1_000,
        )
    }
}
