package ru.hznik.devicebridge.server

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.text.TextMessageId

@RunWith(AndroidJUnit4::class)
class EventNotificationPublisherDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val publisher = AndroidEventNotificationPublisher(
        context = context,
        modelFactory = EventNotificationModelFactory(),
        registry = EventNotificationRegistry(),
    )
    private val notice = EventNotice.IncomingText(
        sessionId = BrowserSessionId("session-1"),
        messageId = TextMessageId("message-1"),
        content = "Секретный текст",
        isLink = false,
    )

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        // Results of earlier real use stay in the shade; only this test's notifications count.
        manager.cancelAll()
    }

    @After
    fun cleanUp() {
        publisher.cancel(notice.key)
    }

    @Test
    fun eventsUseAHighImportanceChannelSeparateFromTheServer() {
        publisher.show(notice, alert = true)

        val channel = manager.getNotificationChannel(AndroidEventNotificationPublisher.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals("События", channel.name)
    }

    @Test
    fun lockScreenVersionHasNoContent() {
        publisher.show(notice, alert = true)

        val posted = awaitEvents { it.size == 1 }.single().notification
        assertEquals("Секретный текст", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertNotNull(posted.publicVersion)
        val lockScreen = posted.publicVersion
        assertEquals("Текст с компьютера", lockScreen.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(lockScreen.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(listOf("Копировать"), posted.actions.map { it.title.toString() })
    }

    @Test
    fun cancelRemovesTheNotification() {
        publisher.show(notice, alert = true)
        awaitEvents { it.isNotEmpty() }

        publisher.cancel(notice.key)

        awaitEvents { it.isEmpty() }
    }

    /** The system posts notifications asynchronously. */
    private fun awaitEvents(
        condition: (List<android.service.notification.StatusBarNotification>) -> Boolean,
    ): List<android.service.notification.StatusBarNotification> {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val events = manager.activeNotifications
                .filter { it.notification.channelId == AndroidEventNotificationPublisher.CHANNEL_ID }
            if (condition(events)) return events
            assertTrue("Notifications did not settle: ${events.size}", System.currentTimeMillis() < deadline)
            Thread.sleep(100)
        }
    }
}
