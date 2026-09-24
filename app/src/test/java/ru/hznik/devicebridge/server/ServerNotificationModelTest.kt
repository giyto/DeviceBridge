package ru.hznik.devicebridge.server

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.file.FileTransferDirection

class ServerNotificationModelTest {

    private val factory = ServerNotificationModelFactory()

    @Test
    fun startingRunningAndStoppingHaveTruthfulModelsWithoutSecrets() {
        val starting = requireNotNull(
            factory.create(ServerLifecycleState.Starting(generation = 1)),
        )
        val running = requireNotNull(
            factory.create(
                ServerLifecycleState.Running(
                    generation = 1,
                    endpoint = ServerEndpoint("192.168.1.24", 49_321),
                    startedAtElapsedRealtimeMs = 10,
                ),
            ),
        )
        val stopping = requireNotNull(
            factory.create(ServerLifecycleState.Stopping(generation = 1)),
        )

        assertEquals("Сервер запускается", starting.title)
        assertEquals("Сервер запущен", running.title)
        assertTrue(running.text.contains("http://192.168.1.24:49321"))
        assertTrue(running.text.contains("0 браузеров"))
        assertTrue(running.text.contains("Передача не выполняется"))
        assertFalse(running.text.contains("token", ignoreCase = true))
        assertFalse(running.text.contains("bearer", ignoreCase = true))
        assertEquals("Сервер останавливается", stopping.title)
        assertTrue(starting.ongoing)
        assertTrue(running.showStopAction)
        assertFalse(stopping.showStopAction)
    }

    @Test
    fun idleCountdownAddsStopTimeOnlyWhileItRuns() {
        val running = ServerLifecycleState.Running(
            generation = 1,
            endpoint = ServerEndpoint("192.168.1.24", 8_787),
            startedAtElapsedRealtimeMs = 10,
        )

        val counting = requireNotNull(factory.create(running, idleStopAtLocalTime = "16:55"))
        val connected = requireNotNull(factory.create(running, activeSessionCount = 1))

        assertTrue(counting.text.endsWith("\nОстановится в 16:55 без подключений"))
        assertFalse(connected.text.contains("Остановится"))
    }

    @Test
    fun stoppedAndErrorDoNotCreateForegroundNotificationModel() {
        assertNull(factory.create(ServerLifecycleState.Stopped))
        assertNull(
            factory.create(
                ServerLifecycleState.Error(
                    generation = 1,
                    cause = ServerLifecycleError.NetworkLost,
                ),
            ),
        )
    }

    @Test
    fun runningModelUsesActualActiveSessionCountWithoutSecrets() {
        val running = ServerLifecycleState.Running(
            generation = 1,
            endpoint = ServerEndpoint("192.168.1.24", 49_321),
            startedAtElapsedRealtimeMs = 10,
        )

        val none = requireNotNull(factory.create(running, activeSessionCount = 0))
        val two = requireNotNull(factory.create(running, activeSessionCount = 2))

        assertTrue(none.text.contains(" • 0 браузеров • "))
        assertTrue(two.text.contains(" • 2 браузера • "))
        assertFalse(two.text.contains("code", ignoreCase = true))
        assertFalse(two.text.contains("token", ignoreCase = true))
        assertFalse(two.text.contains("bearer", ignoreCase = true))
    }

    @Test
    fun runningModelUsesRussianPluralForConnectedBrowserCount() {
        val running = ServerLifecycleState.Running(
            generation = 1,
            endpoint = ServerEndpoint("192.168.1.24", 49_321),
            startedAtElapsedRealtimeMs = 10,
        )

        mapOf(
            1 to "1 браузер",
            2 to "2 браузера",
            4 to "4 браузера",
            5 to "5 браузеров",
            11 to "11 браузеров",
            12 to "12 браузеров",
            21 to "21 браузер",
            22 to "22 браузера",
        ).forEach { (count, expected) ->
            val model = requireNotNull(factory.create(running, activeSessionCount = count))
            assertEquals(
                "http://192.168.1.24:49321 • $expected • Передача не выполняется",
                model.text,
            )
        }
    }

    @Test
    fun androidPublisherCountsOnlyLiveBrowserConnections() {
        val source = Files.readString(
            Path.of(
                "src/main/java/ru/hznik/devicebridge/server/" +
                    "AndroidServerNotificationController.kt",
            ),
        )

        assertTrue(source.contains("connectedSessionIds.value.size"))
        assertFalse(source.contains("sessions.size"))
    }

    @Test
    fun activeTextTransferChangesOnlyGenericNotificationStatus() {
        val running = ServerLifecycleState.Running(
            generation = 1,
            endpoint = ServerEndpoint("192.168.1.24", 49_321),
            startedAtElapsedRealtimeMs = 10,
        )

        val active = requireNotNull(
            factory.create(
                state = running,
                activeSessionCount = 1,
                hasActiveTextTransfer = true,
            ),
        )

        assertTrue(active.text.contains("Передача текста выполняется"))
        assertFalse(active.text.contains("секретное содержимое"))
        assertFalse(active.text.contains("message", ignoreCase = true))
    }

    @Test
    fun activeFileTransferShowsOnlySafeNameDirectionAndProgress() {
        val running = ServerLifecycleState.Running(
            generation = 1,
            endpoint = ServerEndpoint("192.168.1.24", 49_321),
            startedAtElapsedRealtimeMs = 10,
        )

        val model = requireNotNull(
            factory.create(
                state = running,
                activeSessionCount = 1,
                activeFileTransfer = FileNotificationProgress(
                    displayName = "../report.pdf\u202e.exe",
                    direction = FileTransferDirection.BROWSER_TO_ANDROID,
                    bytesTransferred = 50,
                    totalBytes = 100,
                ),
            ),
        )

        assertTrue(model.text.contains("На телефон"))
        assertTrue(model.text.contains("50%"))
        assertTrue(model.text.contains("report.pdf.exe"))
        assertFalse(model.text.contains("../"))
        assertFalse(model.text.contains("content://"))
        assertFalse(model.text.contains("sha256", ignoreCase = true))
    }

    @Test
    fun androidPublisherUsesIdempotentLowPriorityChannelAndImmutableStopAction() {
        val source = Files.readString(
            Path.of(
                "src/main/java/ru/hznik/devicebridge/server/" +
                    "AndroidServerNotificationController.kt",
            ),
        )

        assertTrue(source.contains("IMPORTANCE_LOW"))
        assertTrue(source.contains("createNotificationChannel"))
        assertTrue(source.contains("FLAG_IMMUTABLE"))
        assertTrue(source.contains("ACTION_STOP"))
        assertFalse(source.contains("token", ignoreCase = true))
    }

    @Test
    fun foregroundServiceObservesBrowserSessionStateForCountUpdates() {
        val source = Files.readString(
            Path.of("src/main/java/ru/hznik/devicebridge/server/ServerForegroundService.kt"),
        )

        assertTrue(source.contains("BrowserSessionRepository"))
        assertTrue(source.contains("combine("))
        assertTrue(source.contains("browserSessionRepository.connectedSessionIds"))
    }
}
