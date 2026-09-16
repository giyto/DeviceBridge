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
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.usecase.ApproveBrowserRequestUseCase
import ru.hznik.devicebridge.domain.usecase.DenyBrowserRequestUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveBrowserSessionsUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveServerLifecycleUseCase
import ru.hznik.devicebridge.domain.usecase.RevokeBrowserSessionUseCase
import ru.hznik.devicebridge.domain.usecase.StartServerUseCase
import ru.hznik.devicebridge.domain.usecase.StopServerUseCase

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
) : ViewModel() {
    private val lifecycleState = observeServerLifecycle()
    private val browserSessionState = observeBrowserSessions()
    private val decidingRequestIds = mutableSetOf<PairingRequestId>()
    private val revokingSessionIds = mutableSetOf<BrowserSessionId>()
    private val mutableUiState = kotlinx.coroutines.flow.MutableStateFlow(
        lifecycleState.value.toUiState(
            browserSessionState.value,
            monotonicClock.nowMs(),
        ),
    )
    private val effectChannel = Channel<HomeEffect>(Channel.BUFFERED)
    private var localNetworkCanAskAgain = true

    val uiState: StateFlow<ServerSessionUiState> = mutableUiState
    val effects = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(lifecycleState, browserSessionState) { lifecycle, sessions ->
                lifecycle to sessions
            }.collectLatest { (state, sessions) ->
                mutableUiState.update { previous ->
                    state.toUiState(sessions, monotonicClock.nowMs(), previous)
                }
                if (state is ServerLifecycleState.Running) {
                    uptimeTicker.ticks().collect {
                        mutableUiState.update { previous ->
                            state.toUiState(sessions, monotonicClock.nowMs(), previous)
                        }
                    }
                }
            }
        }
    }

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.StartClicked -> requestPermissionsOrStart()
            HomeAction.StopClicked -> requestStop()
            HomeAction.RetryPermissionClicked -> retryPermission()
            is HomeAction.PermissionsResolved -> {
                localNetworkCanAskAgain = action.localNetworkCanAskAgain
                continueAfterPermissionResult()
            }
            HomeAction.NotificationWarningDismissed -> mutableUiState.update {
                it.copy(showNotificationWarning = false)
            }
            is HomeAction.ApproveBrowser -> decideRequest(
                requestId = action.requestId,
                decision = approveBrowserRequest::invoke,
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
        nowMs: Long,
        previous: ServerSessionUiState = ServerSessionUiState(),
    ): ServerSessionUiState {
        val base = when (this) {
            ServerLifecycleState.Stopped -> ServerSessionUiState()
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
                isPermissionExplanationVisible =
                    cause == ServerLifecycleError.LocalNetworkPermissionDenied ||
                        cause == ServerLifecycleError.PermissionRevoked,
            )
        }
        val showSessions = this is ServerLifecycleState.Running && sessions.isActive
        return base.copy(
            commandPending = false,
            showNotificationWarning = previous.showNotificationWarning,
            isPermissionExplanationVisible =
                base.isPermissionExplanationVisible ||
                    previous.isPermissionExplanationVisible,
            openSettingsForPermission = previous.openSettingsForPermission,
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
                    )
                }
            } else {
                emptyList()
            },
            activeBrowsers = if (showSessions) {
                sessions.sessions.map { session ->
                    ActiveBrowserUiState(
                        id = session.id,
                        browserLabel = session.browserLabel,
                        sourceIpv4 = session.sourceIpv4,
                        actionPending = session.id in revokingSessionIds,
                    )
                }
            } else {
                emptyList()
            },
        )
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
