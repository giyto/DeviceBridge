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

        assertTrue(none.text.contains("0 браузеров"))
        assertTrue(two.text.contains("2 браузеров"))
        assertFalse(two.text.contains("code", ignoreCase = true))
        assertFalse(two.text.contains("token", ignoreCase = true))
        assertFalse(two.text.contains("bearer", ignoreCase = true))
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
    }
}
