package ru.hznik.devicebridge.server

import android.app.Service
import android.app.ForegroundServiceStartNotAllowedException
import android.content.pm.ServiceInfo
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hznik.devicebridge.data.server.IdleStopController
import ru.hznik.devicebridge.data.server.ServerLifecycleCoordinator
import ru.hznik.devicebridge.di.ApplicationScope
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.model.ServerStopReason
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository
import ru.hznik.devicebridge.domain.repository.TextTransferRepository
import ru.hznik.devicebridge.domain.repository.FileTransferRepository

@AndroidEntryPoint
class ServerForegroundService : Service() {

    @Inject
    lateinit var coordinator: ServerLifecycleCoordinator

    @Inject
    lateinit var notificationController: ServerNotificationController

    @Inject
    lateinit var browserSessionRepository: BrowserSessionRepository

    @Inject
    lateinit var textTransferRepository: TextTransferRepository

    @Inject
    lateinit var fileTransferRepository: FileTransferRepository

    @Inject
    lateinit var idleStopController: IdleStopController

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    private var foregroundStarted = false
    private var latestStartId = 0
    private var pendingStopStartId: Int? = null
    private var stateCollectionJob: Job? = null
    private var stopJob: Job? = null
    private var idleStopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        idleStopJob = idleStopController.run(applicationScope) { timeout ->
            withContext(Dispatchers.Main.immediate) {
                // Same path as the notification "Stop" action, only with the idle reason.
                pendingStopStartId = latestStartId
                requestStop(ServerStopReason.IdleTimeout(timeout.minutes ?: 0))
            }
        }
        stateCollectionJob = applicationScope.launch {
            combine(
                coordinator.state,
                browserSessionRepository.state,
                textTransferRepository.state,
                fileTransferRepository.state,
                idleStopController.stopAtWallClockMs,
            ) { lifecycleState, _, _, _, _ -> lifecycleState }
                .collect { state ->
                withContext(Dispatchers.Main.immediate) {
                    if (!foregroundStarted) {
                        return@withContext
                    }
                    when (state) {
                        is ServerLifecycleState.Starting,
                        is ServerLifecycleState.Running,
                        is ServerLifecycleState.Stopping,
                        -> notificationController.publish(state)

                        ServerLifecycleState.Stopped -> Unit

                        is ServerLifecycleState.Error -> {
                            if (coordinator.state.value == state) {
                                finishForegroundService(latestStartId)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            pendingStopStartId = startId
            requestStop()
            return START_NOT_STICKY
        }
        pendingStopStartId = null

        try {
            val notificationState = when (val state = coordinator.state.value) {
                is ServerLifecycleState.Starting,
                is ServerLifecycleState.Running,
                is ServerLifecycleState.Stopping,
                -> state

                ServerLifecycleState.Stopped,
                is ServerLifecycleState.Error,
                -> ServerLifecycleState.Starting(generation = 0)
            }
            ServiceCompat.startForeground(
                this,
                AndroidServerNotificationController.NOTIFICATION_ID,
                notificationController.createForegroundNotification(notificationState),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
            foregroundStarted = true
        } catch (security: SecurityException) {
            reportStartFailure(
                ServerLifecycleError.LocalNetworkPermissionDenied,
                startId,
            )
            return START_NOT_STICKY
        } catch (throwable: Throwable) {
            val lifecycleError = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                Api31ForegroundStartError.matches(throwable)
            ) {
                ServerLifecycleError.ForegroundStartNotAllowed
            } else {
                ServerLifecycleError.ServerStartFailed
            }
            reportStartFailure(lifecycleError, startId)
            return START_NOT_STICKY
        }

        applicationScope.launch {
            coordinator.start()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        idleStopJob?.cancel()
        idleStopJob = null
        if (
            stopJob == null &&
            coordinator.state.value !is ServerLifecycleState.Stopped &&
            coordinator.state.value !is ServerLifecycleState.Error
        ) {
            applicationScope.launch {
                coordinator.stop(ServerStopReason.ProcessTerminated)
            }
        }
        super.onDestroy()
    }

    private fun requestStop(reason: ServerStopReason = ServerStopReason.UserRequested) {
        if (stopJob?.isActive == true) {
            return
        }
        stopJob = applicationScope.launch {
            coordinator.stop(reason)
            withContext(Dispatchers.Main.immediate) {
                pendingStopStartId?.let { stopStartId ->
                    finishForegroundService(stopStartId)
                }
            }
        }
    }

    private fun reportStartFailure(
        error: ServerLifecycleError,
        startId: Int,
    ) {
        foregroundStarted = false
        applicationScope.launch {
            coordinator.reportExternalStartFailure(error)
        }
        notificationController.cancel()
        stopSelfResult(startId)
    }

    private fun finishForegroundService(stopStartId: Int) {
        if (latestStartId != stopStartId) {
            return
        }
        pendingStopStartId = null
        if (foregroundStarted) {
            foregroundStarted = false
            ServiceCompat.stopForeground(
                this,
                ServiceCompat.STOP_FOREGROUND_REMOVE,
            )
        }
        notificationController.cancel()
        stopSelfResult(stopStartId)
    }

    companion object {
        const val ACTION_START = "ru.hznik.devicebridge.action.START_SERVER"
        const val ACTION_STOP = "ru.hznik.devicebridge.action.STOP_SERVER"
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31ForegroundStartError {
        fun matches(throwable: Throwable): Boolean =
            throwable is ForegroundServiceStartNotAllowedException
    }
}
