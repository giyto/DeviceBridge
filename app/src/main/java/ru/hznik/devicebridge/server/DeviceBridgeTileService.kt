package ru.hznik.devicebridge.server

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.hznik.devicebridge.MainActivity
import ru.hznik.devicebridge.R
import ru.hznik.devicebridge.data.permission.ServerPermissionGateway
import ru.hznik.devicebridge.data.permission.ServerPermissionPolicy
import ru.hznik.devicebridge.data.server.ServerLifecycleCoordinator
import ru.hznik.devicebridge.data.server.ServerServiceCommandGateway
import ru.hznik.devicebridge.di.ApplicationScope
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository
import ru.hznik.devicebridge.domain.repository.FileTransferRepository
import ru.hznik.devicebridge.domain.repository.TextTransferRepository

/** Quick Settings tile that mirrors the server lifecycle and starts or stops it with one tap. */
@AndroidEntryPoint
class DeviceBridgeTileService : TileService() {
    @Inject
    lateinit var coordinator: ServerLifecycleCoordinator

    @Inject
    lateinit var browserSessions: BrowserSessionRepository

    @Inject
    lateinit var textTransfers: TextTransferRepository

    @Inject
    lateinit var fileTransfers: FileTransferRepository

    @Inject
    lateinit var permissionGateway: ServerPermissionGateway

    @Inject
    lateinit var serviceCommands: ServerServiceCommandGateway

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    private val permissionPolicy = ServerPermissionPolicy()
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = applicationScope.launch(Dispatchers.Main.immediate) {
            combine(coordinator.state, browserSessions.connectedSessionIds) { state, connected ->
                ServerTilePolicy.model(state, connected.size)
            }
                .distinctUntilChanged()
                .collect(::render)
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        handleClick()
    }

    /**
     * [afterUnlock] is true when called back by [unlockAndRun]: the user has just unlocked, but on
     * Android 10 [isLocked] can still report the keyguard while it animates away.
     */
    private fun handleClick(afterUnlock: Boolean = false) {
        val permissions = permissionGateway.snapshot(localNetworkCanAskAgain = true)
        val canStartWithoutScreen = permissionPolicy.evaluate(
            sdkInt = permissions.sdkInt,
            localNetworkGranted = permissions.localNetworkGranted,
            notificationsGranted = permissions.notificationsGranted,
        ).canStart
        val decision = ServerTilePolicy.click(
            state = coordinator.state.value,
            deviceLocked = isLocked && !afterUnlock,
            canStartWithoutScreen = canStartWithoutScreen,
            hasActiveOperations = ServerTilePolicy.hasActiveOperations(
                textTransfers.state.value,
                fileTransfers.state.value,
            ),
        )
        when (decision) {
            ServerTileClick.StartDirectly -> startDirectly()
            ServerTileClick.OpenAppToStart -> openAppToStart()
            ServerTileClick.UnlockFirst -> unlockAndRun { handleClick(afterUnlock = true) }
            ServerTileClick.Stop -> serviceCommands.requestStop()
            ServerTileClick.ConfirmStop -> showDialog(stopConfirmation())
            ServerTileClick.Ignore -> Unit
        }
    }

    private fun startDirectly() {
        try {
            serviceCommands.requestStart()
        } catch (refused: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: Android wants a visible screen.
            openAppToStart()
            return
        }
        // The service can still be refused startForeground() and then reports it at once.
        applicationScope.launch(Dispatchers.Main.immediate) {
            val outcome = withTimeoutOrNull(START_OUTCOME_TIMEOUT_MS) {
                coordinator.state.drop(1).first { state ->
                    state is ServerLifecycleState.Running || state is ServerLifecycleState.Error
                }
            }
            if (
                outcome is ServerLifecycleState.Error &&
                outcome.cause == ServerLifecycleError.ForegroundStartNotAllowed
            ) {
                openAppToStart()
            }
        }
    }

    // The Intent overload is only reached below API 34, where the PendingIntent one does not exist.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAppToStart() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_START_SERVER_FROM_TILE, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    OPEN_APP_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun stopConfirmation(): AlertDialog =
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Остановить сервер?")
            .setMessage("Сейчас идёт передача. Она будет прервана.")
            .setPositiveButton("Остановить") { _, _ -> serviceCommands.requestStop() }
            .setNegativeButton("Отмена", null)
            .create()

    private fun render(model: ServerTileModel) {
        val tile = qsTile ?: return
        tile.state = when (model.appearance) {
            ServerTileAppearance.ACTIVE -> Tile.STATE_ACTIVE
            ServerTileAppearance.INACTIVE -> Tile.STATE_INACTIVE
            ServerTileAppearance.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
        }
        tile.label = getString(R.string.tile_label)
        tile.subtitle = model.subtitle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = model.subtitle
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_server_notification)
        tile.updateTile()
    }

    companion object {
        private const val OPEN_APP_REQUEST_CODE = 2_089
        private const val START_OUTCOME_TIMEOUT_MS = 3_000L

        fun component(context: Context): ComponentName =
            ComponentName(context, DeviceBridgeTileService::class.java)
    }
}

/**
 * Asks the system to rebind the tile whenever what it shows changes, so it is correct even if
 * the server was started from Home, stopped from the notification or stopped for inactivity.
 */
@Singleton
class ServerTileRefresher @Inject constructor(
    private val coordinator: ServerLifecycleCoordinator,
    private val browserSessions: BrowserSessionRepository,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    fun start(context: Context) {
        val appContext = context.applicationContext
        applicationScope.launch {
            combine(coordinator.state, browserSessions.connectedSessionIds) { state, connected ->
                ServerTilePolicy.model(state, connected.size)
            }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    runCatching {
                        TileService.requestListeningState(
                            appContext,
                            DeviceBridgeTileService.component(appContext),
                        )
                    }
                }
        }
    }
}
