package ru.hznik.devicebridge.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.data.permission.ServerPermissionGateway
import ru.hznik.devicebridge.data.permission.ServerPermissionRequestPlanner
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.usecase.ObserveServerLifecycleUseCase
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
) : ViewModel() {
    private val lifecycleState = observeServerLifecycle()
    private val mutableUiState = kotlinx.coroutines.flow.MutableStateFlow(
        lifecycleState.value.toUiState(monotonicClock.nowMs()),
    )
    private val effectChannel = Channel<HomeEffect>(Channel.BUFFERED)
    private var localNetworkCanAskAgain = true

    val uiState: StateFlow<ServerSessionUiState> = mutableUiState
    val effects = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            lifecycleState.collectLatest { state ->
                mutableUiState.update { previous ->
                    state.toUiState(monotonicClock.nowMs(), previous)
                }
                if (state is ServerLifecycleState.Running) {
                    uptimeTicker.ticks().collect {
                        mutableUiState.update { previous ->
                            state.toUiState(monotonicClock.nowMs(), previous)
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
        return base.copy(
            commandPending = false,
            showNotificationWarning = previous.showNotificationWarning,
            isPermissionExplanationVisible =
                base.isPermissionExplanationVisible ||
                    previous.isPermissionExplanationVisible,
            openSettingsForPermission = previous.openSettingsForPermission,
        )
    }

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
