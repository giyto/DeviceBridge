package ru.hznik.devicebridge.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.file.FileTransferState
import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.text.TextTransferState

class ServerTilePolicyTest {
    private val running = ServerLifecycleState.Running(1, ServerEndpoint("192.168.1.24", 8_787), 0)

    @Test
    fun tileMirrorsEveryLifecycleState() {
        assertEquals(
            ServerTileModel(ServerTileAppearance.ACTIVE, "Запущен · 2 браузера"),
            ServerTilePolicy.model(running, browserCount = 2),
        )
        assertEquals(
            ServerTileModel(ServerTileAppearance.ACTIVE, "Запущен · 0 браузеров"),
            ServerTilePolicy.model(running, browserCount = 0),
        )
        assertEquals(
            ServerTileAppearance.UNAVAILABLE,
            ServerTilePolicy.model(ServerLifecycleState.Starting(1), 0).appearance,
        )
        assertEquals(
            ServerTileAppearance.UNAVAILABLE,
            ServerTilePolicy.model(ServerLifecycleState.Stopping(1), 0).appearance,
        )
        assertEquals(
            ServerTileModel(ServerTileAppearance.INACTIVE, "Остановлен"),
            ServerTilePolicy.model(ServerLifecycleState.Stopped, 3),
        )
        assertEquals(
            ServerTileAppearance.INACTIVE,
            ServerTilePolicy.model(
                ServerLifecycleState.Error(1, ServerLifecycleError.NetworkLost),
                0,
            ).appearance,
        )
    }

    @Test
    fun tileAndRefresherCountOnlyBrowsersWithLiveConnection() {
        val source = java.nio.file.Files.readString(
            java.nio.file.Path.of(
                "src/main/java/ru/hznik/devicebridge/server/DeviceBridgeTileService.kt",
            ),
        )

        // Both the listening tile and the refresher that rebinds it follow live connections.
        assertEquals(2, source.split("browserSessions.connectedSessionIds").size - 1)
        assertFalse(source.contains("sessions.sessions.size"))
    }

    @Test
    fun stoppedTileStartsOnlyWhenUnlockedAndAllowed() {
        fun click(locked: Boolean, allowed: Boolean) = ServerTilePolicy.click(
            ServerLifecycleState.Stopped,
            deviceLocked = locked,
            canStartWithoutScreen = allowed,
            hasActiveOperations = false,
        )
        assertEquals(ServerTileClick.StartDirectly, click(locked = false, allowed = true))
        assertEquals(ServerTileClick.OpenAppToStart, click(locked = false, allowed = false))
        assertEquals(ServerTileClick.UnlockFirst, click(locked = true, allowed = true))
        assertEquals(ServerTileClick.UnlockFirst, click(locked = true, allowed = false))
        assertEquals(
            ServerTileClick.StartDirectly,
            ServerTilePolicy.click(
                ServerLifecycleState.Error(1, ServerLifecycleError.AddressChanged),
                deviceLocked = false,
                canStartWithoutScreen = true,
                hasActiveOperations = false,
            ),
        )
    }

    @Test
    fun runningTileStopsOrAsksWhileTransferring() {
        assertEquals(
            ServerTileClick.Stop,
            ServerTilePolicy.click(running, deviceLocked = true, canStartWithoutScreen = false, hasActiveOperations = false),
        )
        assertEquals(
            ServerTileClick.ConfirmStop,
            ServerTilePolicy.click(running, deviceLocked = false, canStartWithoutScreen = true, hasActiveOperations = true),
        )
        assertEquals(
            ServerTileClick.Ignore,
            ServerTilePolicy.click(ServerLifecycleState.Starting(1), false, true, false),
        )
        assertEquals(
            ServerTileClick.Ignore,
            ServerTilePolicy.click(ServerLifecycleState.Stopping(1), false, true, false),
        )
    }

    @Test
    fun onlyUnfinishedOperationsCountAsActive() {
        val queued = FileTransferState.queued(
            generationId = ServerGenerationId(1),
            ownerSessionId = BrowserSessionId("session-1"),
            metadata = FileTransferMetadata(
                id = FileTransferId("file-1"),
                displayName = "movie.mp4",
                sizeBytes = 10,
                mimeType = "video/mp4",
                sha256 = "a".repeat(64),
                direction = FileTransferDirection.ANDROID_TO_BROWSER,
            ),
        )
        assertFalse(
            ServerTilePolicy.hasActiveOperations(TextTransferState.empty(), FileTransferSnapshot(emptyList())),
        )
        assertTrue(
            ServerTilePolicy.hasActiveOperations(TextTransferState.empty(), FileTransferSnapshot(listOf(queued))),
        )
    }

    @Test
    fun startRequestIsConsumedOnlyByItsOwnId() {
        val requests = ServerStartRequests()
        val first = requests.request()
        val second = requests.request()

        requests.consume(first)
        assertEquals(second, requests.pending.value)
        requests.consume(second)
        assertNull(requests.pending.value)
    }
}
