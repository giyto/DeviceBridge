package ru.hznik.devicebridge.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import ru.hznik.devicebridge.R
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository
import ru.hznik.devicebridge.domain.repository.TextTransferRepository
import ru.hznik.devicebridge.domain.repository.FileTransferRepository
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.text.TextTransferStatus

interface ServerNotificationController {
    fun createForegroundNotification(state: ServerLifecycleState): Notification

    fun publish(state: ServerLifecycleState)

    fun cancel()
}

@Singleton
class AndroidServerNotificationController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val modelFactory: ServerNotificationModelFactory,
    private val browserSessionRepository: BrowserSessionRepository,
    private val textTransferRepository: TextTransferRepository,
    private val fileTransferRepository: FileTransferRepository,
) : ServerNotificationController {

    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    override fun createForegroundNotification(
        state: ServerLifecycleState,
    ): Notification {
        ensureChannel()
        val model = requireNotNull(
            modelFactory.create(
                state = state,
                activeSessionCount = browserSessionRepository.state.value.sessions.size,
                hasActiveTextTransfer = textTransferRepository.hasActiveTransfer(),
                activeFileTransfer = fileTransferRepository.activeNotificationTransfer(),
            ),
        ) {
            "Foreground notification requires an active server state"
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle(model.title)
            .setContentText(model.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(model.text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(model.ongoing)
            .apply {
                if (model.showStopAction) {
                    addAction(
                        0,
                        "Остановить",
                        stopPendingIntent(),
                    )
                }
            }
            .build()
    }

    override fun publish(state: ServerLifecycleState) {
        val model = modelFactory.create(
            state = state,
            activeSessionCount = browserSessionRepository.state.value.sessions.size,
            hasActiveTextTransfer = textTransferRepository.hasActiveTransfer(),
            activeFileTransfer = fileTransferRepository.activeNotificationTransfer(),
        )
        if (model == null) {
            cancel()
            return
        }
        runCatching {
            notificationManager.notify(
                NOTIFICATION_ID,
                createForegroundNotification(state),
            )
        }
    }

    override fun cancel() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Локальный сервер",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Состояние локального сервера DeviceBridge"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        context,
        STOP_REQUEST_CODE,
        Intent(context, ServerForegroundService::class.java)
            .setAction(ServerForegroundService.ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun TextTransferRepository.hasActiveTransfer(): Boolean =
        state.value.items.any {
            it.status == TextTransferStatus.PENDING ||
                it.status == TextTransferStatus.SENDING
        }

    private fun FileTransferRepository.activeNotificationTransfer(): FileNotificationProgress? =
        state.value.items.firstOrNull { item ->
            item.phase == FileTransferPhase.CONNECTING ||
                item.phase == FileTransferPhase.TRANSFERRING ||
                item.phase == FileTransferPhase.VERIFYING
        }?.let { item ->
            FileNotificationProgress(
                displayName = item.metadata.displayName,
                direction = item.metadata.direction,
                bytesTransferred = item.bytesTransferred,
                totalBytes = item.metadata.sizeBytes,
            )
        }

    companion object {
        const val CHANNEL_ID = "devicebridge_server"
        const val NOTIFICATION_ID = 2_087
        private const val STOP_REQUEST_CODE = 2_088
    }
}
