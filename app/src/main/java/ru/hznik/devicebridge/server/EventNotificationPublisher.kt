package ru.hznik.devicebridge.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import ru.hznik.devicebridge.MainActivity
import ru.hznik.devicebridge.R
import ru.hznik.devicebridge.domain.text.TextContentClassifier
import ru.hznik.devicebridge.domain.text.TextContentKind

interface EventNotificationPublisher {
    /** Shows or updates the notification; [alert] is false for a quiet update of one already shown. */
    fun show(notice: EventNotice, alert: Boolean)

    fun cancel(key: EventNotificationKey)
}

/**
 * Remembers which notice each shown notification stands for, so a tapped action carries only
 * a number: no session, message or transfer id and no content leaves the process.
 */
@Singleton
class EventNotificationRegistry @Inject constructor() {
    private val ids = mutableMapOf<EventNotificationKey, Int>()
    private val notices = mutableMapOf<Int, EventNotice>()
    private var nextId = FIRST_ID

    @Synchronized
    fun register(notice: EventNotice): Int {
        val id = ids.getOrPut(notice.key) { nextId++ }
        notices[id] = notice
        return id
    }

    @Synchronized
    fun remove(key: EventNotificationKey): Int? {
        val id = ids.remove(key) ?: return null
        notices.remove(id)
        return id
    }

    @Synchronized
    fun notice(id: Int): EventNotice? = notices[id]

    @Synchronized
    fun textCount(): Int = notices.values.count { it is EventNotice.IncomingText }

    private companion object {
        const val FIRST_ID = 3_000
    }
}

@Singleton
class AndroidEventNotificationPublisher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val modelFactory: EventNotificationModelFactory,
    private val registry: EventNotificationRegistry,
) : EventNotificationPublisher {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    override fun show(notice: EventNotice, alert: Boolean) {
        ensureChannel(notificationManager)
        val id = registry.register(notice)
        val model = modelFactory.create(notice)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle(model.title)
            .setContentText(model.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(model.text))
            .setCategory(categoryOf(notice))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(model, notice))
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true)
            .setContentIntent(openIntent(model.target, id, CONTENT_SLOT))
            .apply {
                model.timeoutMs?.let(::setTimeoutAfter)
                if (notice is EventNotice.IncomingText) setGroup(TEXT_GROUP)
                model.actions.forEach { action ->
                    val intent = actionIntent(id, action, model) ?: return@forEach
                    addAction(0, action.label, intent)
                }
            }
            .build()
        runCatching {
            notificationManager.notify(id, notification)
            if (notice is EventNotice.IncomingText) updateTextSummary()
        }
    }

    override fun cancel(key: EventNotificationKey) {
        val id = registry.remove(key) ?: return
        runCatching {
            notificationManager.cancel(id)
            if (key is EventNotificationKey.Text) updateTextSummary()
        }
    }

    private fun updateTextSummary() {
        val count = registry.textCount()
        if (count < 2) {
            notificationManager.cancel(TEXT_SUMMARY_ID)
            return
        }
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle("Текст с компьютера")
            .setContentText("Сообщений: $count")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(TEXT_GROUP)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent(EventNotificationTarget.TEXT, TEXT_SUMMARY_ID, CONTENT_SLOT))
            .build()
        notificationManager.notify(TEXT_SUMMARY_ID, summary)
    }

    private fun publicVersion(model: EventNotificationModel, notice: EventNotice): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle(model.publicTitle)
            .setCategory(categoryOf(notice))
            .build()

    private fun categoryOf(notice: EventNotice): String = when (notice) {
        is EventNotice.IncomingText -> NotificationCompat.CATEGORY_MESSAGE
        is EventNotice.TransferResult -> NotificationCompat.CATEGORY_STATUS
        is EventNotice.PairingRequest,
        is EventNotice.IncomingFiles,
        -> NotificationCompat.CATEGORY_EVENT
    }

    /**
     * Opens the screen of the event. Android closes a notification by itself only for a tap on its
     * body, so the app closes the one whose "Открыть" or "Показать" was pressed.
     */
    private fun openIntent(target: EventNotificationTarget, notificationId: Int, slot: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId * ACTION_SLOTS + slot,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_SECTION, target.section)
                .putExtra(MainActivity.EXTRA_DISMISS_NOTIFICATION, notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun actionIntent(
        id: Int,
        action: EventNotificationAction,
        model: EventNotificationModel,
    ): PendingIntent? {
        val requestCode = id * ACTION_SLOTS + action.ordinal
        return when (action) {
            EventNotificationAction.OPEN,
            EventNotificationAction.SHOW_FILES,
            -> openIntent(model.target, id, action.ordinal)

            // A receiver may not start an activity on Android 12+, so the link opens directly.
            EventNotificationAction.OPEN_LINK -> {
                val link = model.link
                    ?.takeIf { TextContentClassifier.classify(it) == TextContentKind.LINK }
                    ?: return null
                PendingIntent.getActivity(
                    context,
                    requestCode,
                    Intent(Intent.ACTION_VIEW, Uri.parse(link))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

            EventNotificationAction.ACCEPT_FILES,
            EventNotificationAction.DECLINE_FILES,
            EventNotificationAction.COPY_TEXT,
            -> PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, EventNotificationActionReceiver::class.java)
                    .setAction(EventNotificationActionReceiver.actionName(action))
                    .putExtra(EventNotificationActionReceiver.EXTRA_NOTIFICATION_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "devicebridge_events"

        /** Idempotent; also done before the settings screen links to the channel. */
        fun ensureChannel(notificationManager: NotificationManager) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "События",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Запросы подключения, текст и файлы с компьютера"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            notificationManager.createNotificationChannel(channel)
        }

        /** Events reach the person only if both the app and its event channel may notify. */
        fun eventsEnabled(appNotificationsEnabled: Boolean, channelImportance: Int?): Boolean =
            appNotificationsEnabled && channelImportance != NotificationManager.IMPORTANCE_NONE

        fun eventsEnabled(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            ensureChannel(manager)
            return eventsEnabled(
                appNotificationsEnabled = manager.areNotificationsEnabled(),
                channelImportance = manager.getNotificationChannel(CHANNEL_ID)?.importance,
            )
        }

        /** The channel's own page when the app may notify, otherwise the app's page. */
        fun settingsIntent(context: Context): Intent {
            val manager = context.getSystemService(NotificationManager::class.java)
            return if (manager.areNotificationsEnabled()) {
                Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
            } else {
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            }.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        private const val TEXT_GROUP = "devicebridge_text"
        private const val TEXT_SUMMARY_ID = 2_999
        private const val ACTION_SLOTS = 8

        /** The request-code slot of a tap on the notification body, after those of the actions. */
        private const val CONTENT_SLOT = ACTION_SLOTS - 1
    }
}
