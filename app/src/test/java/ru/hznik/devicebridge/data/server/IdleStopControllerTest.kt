package ru.hznik.devicebridge.data.server

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.file.FileTransferState
import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingChallengeId
import ru.hznik.devicebridge.domain.session.PairingCodeState
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.session.PendingBrowserRequest
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.settings.IdleStopTimeout
import ru.hznik.devicebridge.domain.text.TextTransferState

@OptIn(ExperimentalCoroutinesApi::class)
class IdleStopControllerTest {
    private val minute = 60_000L

    @Test
    fun stopsAfterTheSelectedTimeWithoutConnections() = runTest {
        val fixture = Fixture(this)
        fixture.start()

        advanceTimeBy(30 * minute - 1)
        runCurrent()
        assertEquals(emptyList<IdleStopTimeout>(), fixture.stops)
        assertEquals(WALL_START + 30 * minute, fixture.controller.stopAtWallClockMs.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(IdleStopTimeout.MIN_30), fixture.stops)
        assertNull(fixture.controller.stopAtWallClockMs.value)
    }

    @Test
    fun liveConnectionCancelsTheCountdownAndDisconnectRestartsIt() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        advanceTimeBy(20 * minute)

        fixture.connections.value = 1
        runCurrent()
        assertNull(fixture.controller.stopAtWallClockMs.value)
        advanceTimeBy(60 * minute)
        runCurrent()
        assertEquals(emptyList<IdleStopTimeout>(), fixture.stops)

        fixture.connections.value = 0
        runCurrent()
        advanceTimeBy(30 * minute - 1)
        runCurrent()
        assertEquals(emptyList<IdleStopTimeout>(), fixture.stops)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(IdleStopTimeout.MIN_30), fixture.stops)
    }

    @Test
    fun unfinishedTransferKeepsTheServerRunningPastTheInterval() = runTest {
        val fixture = Fixture(this)
        fixture.files.value = FileTransferSnapshot(listOf(activeFile()))
        fixture.start()

        advanceTimeBy(90 * minute)
        runCurrent()
        assertEquals(emptyList<IdleStopTimeout>(), fixture.stops)

        fixture.files.value = FileTransferSnapshot(emptyList())
        runCurrent()
        advanceTimeBy(30 * minute)
        runCurrent()
        assertEquals(listOf(IdleStopTimeout.MIN_30), fixture.stops)
    }

    @Test
    fun pendingPairingRequestKeepsTheServerRunning() = runTest {
        val fixture = Fixture(this)
        fixture.sessions.value = BrowserSessionState.active(
            generationId = ServerGenerationId(1),
            pairingCode = PairingCodeState("123456", 300_000),
            pendingRequests = listOf(
                PendingBrowserRequest(
                    id = PairingRequestId("request-1"),
                    challengeId = PairingChallengeId("challenge-1"),
                    generationId = ServerGenerationId(1),
                    browserLabel = "Chrome",
                    sourceIpv4 = "192.168.1.2",
                    createdAtElapsedRealtimeMs = 1,
                    expiresAtElapsedRealtimeMs = 60_001,
                ),
            ),
        )
        fixture.start()

        advanceTimeBy(45 * minute)
        runCurrent()
        assertEquals(emptyList<IdleStopTimeout>(), fixture.stops)
        assertNull(fixture.controller.stopAtWallClockMs.value)
    }

    @Test
    fun deadlinePassedDuringSleepFiresOnTheNextTick() = runTest {
        val fixture = Fixture(this)
        fixture.start()

        // Elapsed realtime jumps 40 minutes while coroutine timers were frozen by device sleep.
        fixture.sleptMs = 40 * minute
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf(IdleStopTimeout.MIN_30), fixture.stops)
    }

    @Test
    fun offNeverStopsAndChangingTheSettingRestartsTheCountdown() = runTest {
        val fixture = Fixture(this)
        fixture.timeout.value = IdleStopTimeout.OFF
        fixture.start()
        advanceTimeBy(24 * 60 * minute)
        runCurrent()
        assertEquals(emptyList<IdleStopTimeout>(), fixture.stops)
        assertNull(fixture.controller.stopAtWallClockMs.value)

        fixture.timeout.value = IdleStopTimeout.MIN_15
        runCurrent()
        advanceTimeBy(15 * minute)
        runCurrent()
        assertEquals(listOf(IdleStopTimeout.MIN_15), fixture.stops)
    }

    @Test
    fun countdownRunsOnlyWhileTheServerIsRunning() = runTest {
        val fixture = Fixture(this)
        fixture.lifecycle.value = ServerLifecycleState.Stopped
        fixture.start()
        advanceTimeBy(60 * minute)
        runCurrent()
        assertEquals(emptyList<IdleStopTimeout>(), fixture.stops)

        fixture.lifecycle.value = running(generation = 2)
        runCurrent()
        advanceTimeBy(30 * minute)
        runCurrent()
        assertEquals(listOf(IdleStopTimeout.MIN_30), fixture.stops)
    }

    private class Fixture(private val scope: TestScope) {
        val lifecycle = MutableStateFlow<ServerLifecycleState>(running(generation = 1))
        val connections = MutableStateFlow(0)
        val sessions = MutableStateFlow(BrowserSessionState.inactive())
        val texts = MutableStateFlow(TextTransferState.empty())
        val files = MutableStateFlow(FileTransferSnapshot(emptyList()))
        val timeout = MutableStateFlow(IdleStopTimeout.MIN_30)
        val stops = mutableListOf<IdleStopTimeout>()
        var sleptMs = 0L
        val controller = IdleStopController(
            lifecycle = lifecycle,
            activeConnections = connections,
            sessions = sessions,
            textTransfers = texts,
            fileTransfers = files,
            timeout = timeout,
            clock = MonotonicClock { scope.testScheduler.currentTime + sleptMs },
            wallClockMs = { WALL_START + scope.testScheduler.currentTime },
        )

        fun start() {
            controller.run(scope.backgroundScope) { stops += it }
            scope.runCurrent()
        }
    }

    private companion object {
        const val WALL_START = 1_800_000_000_000L

        fun running(generation: Long) = ServerLifecycleState.Running(
            generation = generation,
            endpoint = ServerEndpoint("10.0.2.16", 8787),
            startedAtElapsedRealtimeMs = 0,
        )

        fun activeFile(): FileTransferState = FileTransferState.queued(
            generationId = ServerGenerationId(1),
            ownerSessionId = BrowserSessionId("session-1"),
            metadata = FileTransferMetadata(
                id = FileTransferId("file-1"),
                displayName = "movie.mp4",
                sizeBytes = 1_000,
                mimeType = "video/mp4",
                sha256 = "a".repeat(64),
                direction = FileTransferDirection.BROWSER_TO_ANDROID,
            ),
        )
    }
}
