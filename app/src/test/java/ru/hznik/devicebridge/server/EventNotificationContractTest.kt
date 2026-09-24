package ru.hznik.devicebridge.server

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventNotificationContractTest {

    private val manifest by lazy { Files.readString(Path.of("src/main/AndroidManifest.xml")) }
    private val publisher by lazy {
        Files.readString(Path.of("src/main/java/ru/hznik/devicebridge/server/EventNotificationPublisher.kt"))
    }
    private val serverPublisher by lazy {
        Files.readString(
            Path.of("src/main/java/ru/hznik/devicebridge/server/AndroidServerNotificationController.kt"),
        )
    }

    @Test
    fun theOnlyReceiverIsThePrivateNotificationActionReceiver() {
        val receivers = Regex("<receiver[\\s\\S]*?(/>|</receiver>)").findAll(manifest).map { it.value }.toList()

        assertEquals(1, receivers.size)
        val receiver = receivers.single()
        assertTrue(receiver.contains("android:name=\".server.EventNotificationActionReceiver\""))
        assertTrue(receiver.contains("android:exported=\"false\""))
        assertFalse(receiver.contains("intent-filter"))
    }

    @Test
    fun eventsUseTheirOwnHighPriorityChannelAndHideContentOnTheLockScreen() {
        assertTrue(publisher.contains("\"devicebridge_events\""))
        assertTrue(publisher.contains("IMPORTANCE_HIGH"))
        assertTrue(publisher.contains("VISIBILITY_PRIVATE"))
        assertTrue(publisher.contains("setPublicVersion"))
        // The server notification stays quiet in its own channel.
        assertTrue(serverPublisher.contains("IMPORTANCE_LOW"))
        assertFalse(serverPublisher.contains("devicebridge_events"))
    }

    @Test
    fun eventsNeedBothTheAppAndTheChannelToBeAllowed() {
        assertTrue(AndroidEventNotificationPublisher.eventsEnabled(true, null))
        assertTrue(AndroidEventNotificationPublisher.eventsEnabled(true, 4))
        assertFalse(AndroidEventNotificationPublisher.eventsEnabled(true, 0))
        assertFalse(AndroidEventNotificationPublisher.eventsEnabled(false, 4))
    }

    @Test
    fun notificationIntentsAreImmutableAndCarryNoContent() {
        val pendingIntents = Regex("PendingIntent\\.get(Activity|Broadcast)\\(").findAll(publisher).count()
        val immutable = Regex("PendingIntent\\.FLAG_IMMUTABLE").findAll(publisher).count()

        assertTrue(pendingIntents > 0)
        assertEquals(pendingIntents, immutable)
        val extras = Regex("putExtra\\(([^,]+),").findAll(publisher).map { it.groupValues[1].trim() }.toSet()
        assertEquals(
            setOf(
                "MainActivity.EXTRA_OPEN_SECTION",
                "MainActivity.EXTRA_DISMISS_NOTIFICATION",
                "EventNotificationActionReceiver.EXTRA_NOTIFICATION_ID",
                // The system settings page of the channel.
                "android.provider.Settings.EXTRA_CHANNEL_ID",
                "android.provider.Settings.EXTRA_APP_PACKAGE",
            ),
            extras,
        )
        assertFalse(publisher.contains("token", ignoreCase = true))
    }
}
