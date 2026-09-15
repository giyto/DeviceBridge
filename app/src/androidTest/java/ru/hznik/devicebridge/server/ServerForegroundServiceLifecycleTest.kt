package ru.hznik.devicebridge.server

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoints
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.MainActivity
import ru.hznik.devicebridge.data.server.ServerLifecycleCoordinator
import ru.hznik.devicebridge.di.ServerLifecycleEntryPoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.model.ServerStopReason

@RunWith(AndroidJUnit4::class)
class ServerForegroundServiceLifecycleTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)
    private val coordinator: ServerLifecycleCoordinator
        get() = EntryPoints.get(
            context.applicationContext,
            ServerLifecycleEntryPoint::class.java,
        ).coordinator()

    @Before
    fun prepareStoppedStateAndPermissions() {
        runBlocking { coordinator.stop(ServerStopReason.UserRequested) }
        notificationManager.cancel(AndroidServerNotificationController.NOTIFICATION_ID)
        grantRuntimePermissions()
    }

    @After
    fun stopService() {
        context.startService(
            Intent(context, ServerForegroundService::class.java)
                .setAction(ServerForegroundService.ACTION_STOP),
        )
        awaitState { it is ServerLifecycleState.Stopped }
    }

    @Test
    fun activityBackgroundAndRecreationKeepOneServiceSocketUptimeAndNotification() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ContextCompat.startForegroundService(
                context,
                Intent(context, ServerForegroundService::class.java)
                    .setAction(ServerForegroundService.ACTION_START),
            )
            val first = awaitRunning()
            assertSocketOpen(first.endpoint.port)
            val firstNotification = awaitNotification { notification ->
                notification.notification.extras
                    .getString("android.text")
                    .orEmpty()
                    .contains(first.endpoint.url)
            }
            assertEquals(1, activeServerNotifications().size)
            assertEquals(
                NotificationManager.IMPORTANCE_LOW,
                notificationManager.getNotificationChannel(
                    AndroidServerNotificationController.CHANNEL_ID,
                ).importance,
            )
            assertTrue(
                firstNotification.notification.extras
                    .getString("android.text")
                    .orEmpty()
                    .contains(first.endpoint.url),
            )

            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(250)
            assertSameRunningInstance(first, awaitRunning())
            assertSocketOpen(first.endpoint.port)

            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.recreate()
            assertSameRunningInstance(first, awaitRunning())

            ContextCompat.startForegroundService(
                context,
                Intent(context, ServerForegroundService::class.java)
                    .setAction(ServerForegroundService.ACTION_START),
            )
            SystemClock.sleep(250)
            assertSameRunningInstance(first, awaitRunning())
            assertEquals(1, activeServerNotifications().size)

            val stopAction = awaitNotification().notification.actions
                .singleOrNull { it.title.toString() == "Остановить" }
            assertNotNull(stopAction)
            stopAction!!.actionIntent.send()
            awaitState { it is ServerLifecycleState.Stopped }
            awaitNoNotification()
            assertFalse(canConnect(first.endpoint.port))
        }
    }

    @Test
    fun repeatedStopCommandsStillRemoveServiceNotificationAndSocket() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, ServerForegroundService::class.java)
                .setAction(ServerForegroundService.ACTION_START),
        )
        val running = awaitRunning()
        awaitNotification()
        assertSocketOpen(running.endpoint.port)

        repeat(2) {
            context.startService(
                Intent(context, ServerForegroundService::class.java)
                    .setAction(ServerForegroundService.ACTION_STOP),
            )
        }

        awaitState { it is ServerLifecycleState.Stopped }
        awaitNoNotification()
        assertFalse(canConnect(running.endpoint.port))
    }

    private fun grantRuntimePermissions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        if (Build.VERSION.SDK_INT >= 37) {
            automation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            )
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK),
            )
        }
    }

    private fun awaitRunning(): ServerLifecycleState.Running =
        awaitState { it is ServerLifecycleState.Running } as ServerLifecycleState.Running

    private fun assertSameRunningInstance(
        expected: ServerLifecycleState.Running,
        actual: ServerLifecycleState.Running,
    ) {
        assertEquals(expected.generation, actual.generation)
        assertEquals(expected.endpoint, actual.endpoint)
        assertEquals(
            expected.startedAtElapsedRealtimeMs,
            actual.startedAtElapsedRealtimeMs,
        )
        assertTrue(SystemClock.elapsedRealtime() >= actual.startedAtElapsedRealtimeMs)
    }

    private fun awaitState(
        predicate: (ServerLifecycleState) -> Boolean,
    ): ServerLifecycleState {
        val deadline = SystemClock.elapsedRealtime() + TEST_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            coordinator.state.value.let { state ->
                if (predicate(state)) {
                    return state
                }
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        error("Timed out waiting for server state; actual=" + coordinator.state.value)
    }

    private fun awaitNotification(
        predicate: (android.service.notification.StatusBarNotification) -> Boolean = { true },
    ): android.service.notification.StatusBarNotification {
        val deadline = SystemClock.elapsedRealtime() + TEST_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            activeServerNotifications().singleOrNull()
                ?.takeIf(predicate)
                ?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        error("Timed out waiting for the expected server notification")
    }

    private fun awaitNoNotification() {
        val deadline = SystemClock.elapsedRealtime() + TEST_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (activeServerNotifications().isEmpty()) {
                return
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        error("Server notification was not removed")
    }

    private fun activeServerNotifications() = notificationManager.activeNotifications
        .filter { it.id == AndroidServerNotificationController.NOTIFICATION_ID }

    private fun assertSocketOpen(port: Int) {
        assertTrue(canConnect(port))
    }

    private fun canConnect(port: Int): Boolean = runCatching {
        Socket("127.0.0.1", port).use { it.isConnected }
    }.getOrDefault(false)

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 10_000L
        const val POLL_INTERVAL_MILLIS = 50L
    }
}
