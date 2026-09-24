package ru.hznik.devicebridge.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.data.permission.ServerPermissionGateway
import ru.hznik.devicebridge.data.permission.ServerPermissionRequestPlanner
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.model.ServerStopReason
import ru.hznik.devicebridge.domain.error.toUserFacingFailure
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserApprovalDecision
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.usecase.ApproveBrowserRequestUseCase
import ru.hznik.devicebridge.domain.usecase.DenyBrowserRequestUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveBrowserSessionsUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveServerLifecycleUseCase
import ru.hznik.devicebridge.domain.usecase.RevokeBrowserSessionUseCase
import ru.hznik.devicebridge.domain.usecase.StartServerUseCase
import ru.hznik.devicebridge.domain.usecase.StopServerUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveTextTransfersUseCase
import ru.hznik.devicebridge.domain.text.TextTransferState
import ru.hznik.devicebridge.domain.text.TextTransferStatus
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.usecase.ObserveFileTransfersUseCase

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val startServer: StartServerUseCase,
    private val stopServer: StopServerUseCase,
    observeServerLifecycle: ObserveServerLifecycleUseCase,
    private val permissionGateway: ServerPermissionGateway,
    private val permissionPlanner: ServerPermissionRequestPlanner,
    private val monotonicClock: MonotonicClock,
    private val uptimeTicker: HomeUptimeTicker,
    observeBrowserSessions: ObserveBrowserSessionsUseCase,
    private val approveBrowserRequest: ApproveBrowserRequestUseCase,
    private val denyBrowserRequest: DenyBrowserRequestUseCase,
    private val revokeBrowserSession: RevokeBrowserSessionUseCase,
    observeTextTransfers: ObserveTextTransfersUseCase,
    observeFileTransfers: ObserveFileTransfersUseCase,
    private val autoAcceptStatus: ru.hznik.devicebridge.domain.file.AutoAcceptStatusSource =
        ru.hznik.devicebridge.domain.file.AutoAcceptStatusSource.None,
    private val serverStartRequests: ru.hznik.devicebridge.server.ServerStartRequests =
        ru.hznik.devicebridge.server.ServerStartRequests(),
) : ViewModel() {
    private val lifecycleState = observeServerLifecycle()
    private val lastStopReason = observeServerLifecycle.lastStopReason()
    private val browserSessionState = observeBrowserSessions()
    private val connectedSessionIds = observeBrowserSessions.connectedSessionIds()
    private val textTransferState = observeTextTransfers()
    private val fileTransferState = observeFileTransfers()
    private val decidingRequestIds = mutableSetOf<PairingRequestId>()
    private val revokingSessionIds = mutableSetOf<BrowserSessionId>()
    private val mutableUiState = kotlinx.coroutines.flow.MutableStateFlow(
        lifecycleState.value.toUiState(
            browserSessionState.value,
            connectedSessionIds.value,
            textTransferState.value,
            fileTransferState.value,
            monotonicClock.nowMs(),
        ),
    )
    private val effectChannel = Channel<HomeEffect>(Channel.BUFFERED)
    private var localNetworkCanAskAgain = true

    val uiState: StateFlow<ServerSessionUiState> = mutableUiState
    val effects = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            // A tile tap that needed the visible screen continues as the same explicit start.
            serverStartRequests.pending.collect { requestId ->
                if (requestId != null) {
                    serverStartRequests.consume(requestId)
                    requestPermissionsOrStart()
                }
            }
        }
        viewModelScope.launch {
            combine(
                lifecycleState,
                combine(browserSessionState, connectedSessionIds, ::Pair),
                textTransferState,
                fileTransferState,
                autoAcceptStatus.autoAccepted,
            ) { lifecycle, (sessions, connectedIds), textTransfers, fileTransfers, _ ->
                HomeSourceState(lifecycle, sessions, connectedIds, textTransfers, fileTransfers)
            }.collectLatest { source ->
                val state = source.lifecycle
                mutableUiState.update { previous ->
                    state.toUiState(
                        source.sessions,
                        source.connectedSessionIds,
                        source.textTransfers,
                        source.fileTransfers,
                        monotonicClock.nowMs(),
                        previous,
                    )
                }
                if (state is ServerLifecycleState.Running) {
                    uptimeTicker.ticks().collect {
                        mutableUiState.update { previous ->
                            state.toUiState(
                                source.sessions,
                                source.connectedSessionIds,
                                source.textTransfers,
                                source.fileTransfers,
                                monotonicClock.nowMs(),
                                previous,
                            )
                        }
                    }
                }
            }
        }
    }

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.StartClicked -> requestPermissionsOrStart()
            HomeAction.StartAgainClicked -> requestPermissionsOrStart()
            HomeAction.StopClicked -> requestStop()
            HomeAction.RetryPermissionClicked -> retryPermission()
            HomeAction.RequestPermissionClicked -> requestPermissionsOrStart()
            HomeAction.OpenSettingsClicked -> effectChannel.trySend(HomeEffect.OpenAppSettings)
            is HomeAction.PermissionsResolved -> {
                localNetworkCanAskAgain = action.localNetworkCanAskAgain
                continueAfterPermissionResult()
            }
            HomeAction.NotificationWarningDismissed -> mutableUiState.update {
                it.copy(showNotificationWarning = false)
            }
            is HomeAction.ApproveBrowser -> decideRequest(
                requestId = action.requestId,
                decision = { requestId ->
                    approveBrowserRequest(requestId, BrowserApprovalDecision.ALLOW_ONCE)
                },
            )
            is HomeAction.ApproveAndRememberBrowser -> decideRequest(
                requestId = action.requestId,
                decision = { requestId ->
                    approveBrowserRequest(requestId, BrowserApprovalDecision.ALLOW_AND_REMEMBER)
                },
            )
            is HomeAction.DenyBrowser -> decideRequest(
                requestId = action.requestId,
                decision = denyBrowserRequest::invoke,
            )
            is HomeAction.RevokeBrowser -> revokeSession(action.sessionId)
        }
    }

    private fun decideRequest(
        requestId: PairingRequestId,
        decision: suspend (PairingRequestId) -> Unit,
    ) {
        if (browserSessionState.value.pendingRequests.none { it.id == requestId }) return
        if (!decidingRequestIds.add(requestId)) return
        refreshSessionUi()
        viewModelScope.launch {
            try {
                decision(requestId)
            } finally {
                decidingRequestIds.remove(requestId)
                refreshSessionUi()
            }
        }
    }

    private fun revokeSession(sessionId: BrowserSessionId) {
        if (browserSessionState.value.sessions.none { it.id == sessionId }) return
        if (!revokingSessionIds.add(sessionId)) return
        refreshSessionUi()
        viewModelScope.launch {
            try {
                revokeBrowserSession(sessionId)
            } finally {
                revokingSessionIds.remove(sessionId)
                refreshSessionUi()
            }
        }
    }

    private fun refreshSessionUi() {
        mutableUiState.update { previous ->
            lifecycleState.value.toUiState(
                browserSessionState.value,
                connectedSessionIds.value,
                textTransferState.value,
                fileTransferState.value,
                monotonicClock.nowMs(),
                previous,
            )
        }
    }

    private fun requestPermissionsOrStart() {
        if (!mutableUiState.value.canStart) return
        val plan = permissionPlanner.plan(
            permissionGateway.snapshot(localNetworkCanAskAgain),
        )
        mutableUiState.update {
            it.copy(
                showNotificationWarning = plan.showNotificationWarning,
                isPermissionExplanationVisible = plan.localNetworkBlockedPermanently,
                openSettingsForPermission = plan.localNetworkBlockedPermanently,
            )
        }
        when {
            plan.localNetworkBlockedPermanently -> Unit
            plan.permissions.isNotEmpty() ->
                effectChannel.trySend(HomeEffect.RequestPermissions(plan.permissions))
            plan.canStart -> launchStart()
        }
    }

    private fun continueAfterPermissionResult() {
        val plan = permissionPlanner.plan(
            permissionGateway.snapshot(localNetworkCanAskAgain),
        )
        mutableUiState.update {
            it.copy(
                showNotificationWarning = plan.showNotificationWarning,
                isPermissionExplanationVisible = !plan.canStart,
                openSettingsForPermission = plan.localNetworkBlockedPermanently,
            )
        }
        if (plan.canStart) launchStart()
    }

    private fun retryPermission() {
        if (mutableUiState.value.openSettingsForPermission) {
            effectChannel.trySend(HomeEffect.OpenAppSettings)
        } else {
            requestPermissionsOrStart()
        }
    }

    private fun launchStart() {
        if (!mutableUiState.value.canStart) return
        mutableUiState.update {
            it.copy(
                commandPending = true,
                isPermissionExplanationVisible = false,
                openSettingsForPermission = false,
            )
        }
        viewModelScope.launch { startServer() }
    }

    private fun requestStop() {
        if (!mutableUiState.value.canStop) return
        mutableUiState.update { it.copy(commandPending = true) }
        viewModelScope.launch { stopServer() }
    }

    private fun ServerLifecycleState.toUiState(
        sessions: BrowserSessionState,
        connectedSessionIds: Set<BrowserSessionId>,
        transfers: TextTransferState,
        fileTransfers: FileTransferSnapshot,
        nowMs: Long,
        previous: ServerSessionUiState = ServerSessionUiState(),
    ): ServerSessionUiState {
        val base = when (this) {
            ServerLifecycleState.Stopped -> ServerSessionUiState(
                idleStoppedAfterMinutes =
                    (lastStopReason.value as? ServerStopReason.IdleTimeout)?.minutes,
            )
            is ServerLifecycleState.Starting -> ServerSessionUiState(
                status = HomeServerStatus.Starting,
            )
            is ServerLifecycleState.Running -> ServerSessionUiState(
                status = HomeServerStatus.Running,
                localAddress = endpoint.url,
                uptimeSeconds = ((nowMs - startedAtElapsedRealtimeMs) / 1_000)
                    .coerceAtLeast(0),
            )
            is ServerLifecycleState.Stopping -> ServerSessionUiState(
                status = HomeServerStatus.Stopping,
            )
            is ServerLifecycleState.Error -> ServerSessionUiState(
                status = HomeServerStatus.Error,
                errorMessage = cause.userMessage(),
                failure = cause.toUserFacingFailure(),
            )
        }
        val showSessions = this is ServerLifecycleState.Running && sessions.isActive
        return base.copy(
            commandPending = false,
            showNotificationWarning = previous.showNotificationWarning,
            isPermissionExplanationVisible =
                previous.isPermissionExplanationVisible &&
                    this is ServerLifecycleState.Stopped,
            openSettingsForPermission =
                previous.openSettingsForPermission &&
                    this is ServerLifecycleState.Stopped,
            pairingCode = sessions.pairingCode?.value.takeIf { showSessions },
            pairingExpiresInSeconds = sessions.pairingCode
                ?.expiresAtElapsedRealtimeMs
                ?.remainingSeconds(nowMs)
                ?.takeIf { showSessions },
            pendingBrowsers = if (showSessions) {
                sessions.pendingRequests.map { request ->
                    PendingBrowserUiState(
                        id = request.id,
                        browserLabel = request.browserLabel,
                        sourceIpv4 = request.sourceIpv4,
                        expiresInSeconds = request.expiresAtElapsedRealtimeMs
                            .remainingSeconds(nowMs),
                        actionPending = request.id in decidingRequestIds,
                        rememberBrowserRequested = request.rememberBrowserRequested,
                    )
                }
            } else {
                emptyList()
            },
            // A session whose tab was closed stays listed (it can reconnect or be revoked) but is
            // offline; connected browsers come first.
            activeBrowsers = if (showSessions) {
                sessions.sessions.map { session ->
                    ActiveBrowserUiState(
                        id = session.id,
                        browserLabel = session.browserLabel,
                        sourceIpv4 = session.sourceIpv4,
                        actionPending = session.id in revokingSessionIds,
                        connected = session.id in connectedSessionIds,
                    )
                }.sortedByDescending { it.connected }
            } else {
                emptyList()
            },
            textTransferStatus = if (this is ServerLifecycleState.Running) {
                transfers.toHomeTextTransferStatus()
            } else {
                HomeTextTransferStatus.Idle
            },
            activeFileTransfers = if (this is ServerLifecycleState.Running) {
                fileTransfers.items.filterNot { it.phase.isTerminal }.map { item ->
                    HomeFileTransferUiState(
                        id = item.metadata.id,
                        displayName = item.metadata.displayName,
                        sizeBytes = item.metadata.sizeBytes,
                        direction = item.metadata.direction,
                        phase = item.phase,
                        bytesTransferred = item.bytesTransferred,
                        autoAccepted = item.metadata.id in autoAcceptStatus.autoAccepted.value,
                        resumedFromBytes = item.resumedFromBytes,
                        mimeType = item.metadata.mimeType,
                        speedBytesPerSecond = item.speedBytesPerSecond,
                        senderLabel = sessions.sessions
                            .firstOrNull { it.id == item.ownerSessionId }
                            ?.browserLabel,
                    )
                }
            } else {
                emptyList()
            },
        )
    }

    private fun TextTransferState.toHomeTextTransferStatus(): HomeTextTransferStatus {
        if (
            items.any {
                it.status == TextTransferStatus.PENDING ||
                    it.status == TextTransferStatus.SENDING
            }
        ) {
            return HomeTextTransferStatus.Active
        }
        return when (items.maxByOrNull { it.updatedAtEpochMillis }?.status) {
            TextTransferStatus.DELIVERED -> HomeTextTransferStatus.Completed
            TextTransferStatus.FAILED -> HomeTextTransferStatus.Failed
            TextTransferStatus.PENDING,
            TextTransferStatus.SENDING,
            null,
            -> HomeTextTransferStatus.Idle
        }
    }

    private fun Long.remainingSeconds(nowMs: Long): Long =
        ((this - nowMs).coerceAtLeast(0) + 999) / 1_000

    private fun ServerLifecycleError.userMessage(): String = when (this) {
        ServerLifecycleError.LocalNetworkPermissionDenied,
        ServerLifecycleError.PermissionRevoked,
        -> "Нет разрешения на доступ к локальной сети."
        ServerLifecycleError.NoLanNetwork -> "Подключитесь к Wi-Fi или точке доступа."
        ServerLifecycleError.AmbiguousLanNetwork -> "Не удалось однозначно выбрать локальную сеть."
        ServerLifecycleError.NetworkLost -> "Соединение с локальной сетью потеряно."
        ServerLifecycleError.AddressChanged -> "Адрес телефона в локальной сети изменился."
        ServerLifecycleError.ForegroundStartNotAllowed ->
            "Android не разрешил фоновый запуск. Откройте приложение и повторите."
        ServerLifecycleError.ServerStartFailed -> "Не удалось запустить локальный сервер."
        ServerLifecycleError.StopTimedOut -> "Остановка заняла слишком много времени."
        is ServerLifecycleError.Unexpected -> "Произошла непредвиденная ошибка сервера."
    }
}

private data class HomeSourceState(
    val lifecycle: ServerLifecycleState,
    val sessions: BrowserSessionState,
    val connectedSessionIds: Set<BrowserSessionId>,
    val textTransfers: TextTransferState,
    val fileTransfers: FileTransferSnapshot,
)
