package ru.hznik.devicebridge.server

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import ru.hznik.devicebridge.domain.session.BrowserApprovalDecision
import ru.hznik.devicebridge.domain.usecase.ApproveBrowserRequestUseCase
import ru.hznik.devicebridge.domain.usecase.DenyBrowserRequestUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hznik.devicebridge.data.file.NotificationAcceptedTransfers
import ru.hznik.devicebridge.di.ApplicationScope
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.repository.FileTransferRepository
import ru.hznik.devicebridge.domain.repository.TextTransferRepository
import ru.hznik.devicebridge.domain.session.BrowserSessionId

fun interface TextClipboard {
    suspend fun copy(text: String)
}

class AndroidTextClipboard @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : TextClipboard {
    override suspend fun copy(text: String) = withContext(Dispatchers.Main) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("DeviceBridge", text))
    }
}

/**
 * What the buttons of an event notification do. The notification carries only its own number;
 * the text and the files are looked up in the current state, so a stale button does nothing.
 */
class EventNotificationActionHandler @Inject constructor(
    private val registry: EventNotificationRegistry,
    private val publisher: EventNotificationPublisher,
    private val texts: TextTransferRepository,
    private val files: FileTransferRepository,
    private val notificationAccepted: NotificationAcceptedTransfers,
    private val clipboard: TextClipboard,
    private val approveRequest: ApproveBrowserRequestUseCase,
    private val denyRequest: DenyBrowserRequestUseCase,
) {
    suspend fun handle(action: EventNotificationAction, notificationId: Int) {
        val notice = registry.notice(notificationId) ?: return
        when (action) {
            EventNotificationAction.COPY_TEXT -> {
                if (notice !is EventNotice.IncomingText) return
                val item = texts.state.value.items.firstOrNull {
                    it.sessionId == notice.sessionId && it.id == notice.messageId
                }
                item?.let { clipboard.copy(it.content) }
                publisher.cancel(notice.key)
            }

            // The notification goes away by itself once nothing in the offer waits any more.
            EventNotificationAction.ACCEPT_FILES -> {
                if (notice !is EventNotice.IncomingFiles) return
                notificationAccepted.accept(files.state.value.awaiting(notice.sessionId))
            }

            EventNotificationAction.DECLINE_FILES -> {
                if (notice !is EventNotice.IncomingFiles) return
                files.state.value.awaiting(notice.sessionId).forEach { files.cancel(it) }
            }

            // Same calls as the buttons on the home screen; a request that is no longer pending
            // stays as it is, and the notification goes away either way.
            EventNotificationAction.DENY_PAIRING,
            EventNotificationAction.ALLOW_PAIRING,
            EventNotificationAction.ALLOW_AND_REMEMBER_PAIRING,
            -> {
                if (notice !is EventNotice.PairingRequest) return
                runCatching {
                    when (action) {
                        EventNotificationAction.DENY_PAIRING -> denyRequest(notice.requestId)
                        EventNotificationAction.ALLOW_AND_REMEMBER_PAIRING ->
                            approveRequest(notice.requestId, BrowserApprovalDecision.ALLOW_AND_REMEMBER)
                        else -> approveRequest(notice.requestId, BrowserApprovalDecision.ALLOW_ONCE)
                    }
                }
                publisher.cancel(notice.key)
            }

            EventNotificationAction.OPEN,
            EventNotificationAction.OPEN_LINK,
            EventNotificationAction.SHOW_FILES,
            -> Unit
        }
    }

    /** Files of the offer that have not started: a started one is no longer the person's call here. */
    private fun FileTransferSnapshot.awaiting(sessionId: BrowserSessionId): List<FileTransferId> =
        items.filter { item ->
            item.ownerSessionId == sessionId &&
                item.metadata.direction == FileTransferDirection.BROWSER_TO_ANDROID &&
                (item.phase == FileTransferPhase.QUEUED || item.phase == FileTransferPhase.CONNECTING)
        }.map { it.metadata.id }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface EventNotificationActionEntryPoint {
    fun handler(): EventNotificationActionHandler

    @ApplicationScope
    fun applicationScope(): CoroutineScope
}

/** Not exported: only this app's own notification buttons reach it. */
class EventNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = EventNotificationAction.entries.firstOrNull { actionName(it) == intent.action }
            ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            EventNotificationActionEntryPoint::class.java,
        )
        val pending = goAsync()
        entryPoint.applicationScope().launch {
            try {
                entryPoint.handler().handle(action, notificationId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_NOTIFICATION_ID = "ru.hznik.devicebridge.extra.EVENT_NOTIFICATION_ID"

        fun actionName(action: EventNotificationAction): String =
            "ru.hznik.devicebridge.action.EVENT_${action.name}"
    }
}
