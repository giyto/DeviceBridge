package ru.hznik.devicebridge.server

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferEvent
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferReducer
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.file.FileTransferState
import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingCodeState
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.settings.DestinationTree
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferItem
import ru.hznik.devicebridge.domain.text.TextTransferState
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId

@OptIn(ExperimentalCoroutinesApi::class)
class EventNotificationCoordinatorTest {

    private val sessions = MutableStateFlow(BrowserSessionState.inactive())
    private val transfers = MutableStateFlow(FileTransferSnapshot(emptyList()))
    private val texts = MutableStateFlow(TextTransferState.empty())
    private val settings = MutableStateFlow(DeviceSettings.defaults())
    private val visible = MutableStateFlow(false)
    private val shown = mutableListOf<EventNotice>()
    private val cancelled = mutableListOf<EventNotificationKey>()
    private val coordinator = EventNotificationCoordinator(
        sessions = sessions,
        transfers = transfers,
        texts = texts,
        autoAccepted = MutableStateFlow(emptySet()),
        paused = MutableStateFlow(emptySet()),
        acceptedFromNotification = MutableStateFlow(emptySet()),
        settings = settings,
        appVisible = visible,
        isResumeRetry = { false },
        clock = { 0 },
        publisher = object : EventNotificationPublisher {
            override fun show(notice: EventNotice, alert: Boolean) {
                shown += notice
            }

            override fun cancel(key: EventNotificationKey) {
                cancelled += key
            }
        },
    )

    @Test
    fun serverStopRemovesTextButKeepsTheResult() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        sessions.value = active(ORDINARY)
        transfers.value = FileTransferSnapshot(listOf(upload("t1").started()))
        transfers.value = FileTransferSnapshot(listOf(upload("t1").completed()))
        texts.value = TextTransferState.of(listOf(text("m1")))

        sessions.value = BrowserSessionState.inactive()

        assertEquals(2, shown.size)
        assertEquals(listOf(EventNotificationKey.Text(ORDINARY.id, TextMessageId("m1"))), cancelled)
    }

    @Test
    fun nothingIsShownWhileTheAppIsOnScreen() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        visible.value = true
        sessions.value = active(ORDINARY)

        texts.value = TextTransferState.of(listOf(text("m1")))
        visible.value = false

        assertTrue(shown.isEmpty())
    }

    @Test
    fun trustedBrowserFilesAreNotOfferedWhenTheyAreAcceptedAutomatically() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        settings.value = DeviceSettings.defaults().copy(
            autoAcceptTrustedFiles = true,
            destinationTree = DestinationTree("content://documents/tree/inbox"),
        )
        coordinator.start(backgroundScope)
        sessions.value = active(TRUSTED, ORDINARY)

        transfers.value = FileTransferSnapshot(
            listOf(upload("t1", TRUSTED.id), upload("o1", ORDINARY.id)),
        )

        val offer = shown.single() as EventNotice.IncomingFiles
        assertEquals(ORDINARY.id, offer.sessionId)
    }

    private fun active(vararg browsers: BrowserSession) = BrowserSessionState.active(
        generationId = GENERATION,
        pairingCode = PairingCodeState("123456", 60_000),
        sessions = browsers.toList(),
    )

    private fun text(id: String) = TextTransferItem.incoming(
        id = TextMessageId(id),
        generationId = GENERATION,
        sessionId = ORDINARY.id,
        browserLabel = "Edge",
        content = "Привет",
        contentKind = TextContentKind.TEXT,
        receivedAtEpochMillis = 1_000,
    )

    private fun upload(id: String, session: BrowserSessionId = ORDINARY.id) = FileTransferState.queued(
        generationId = GENERATION,
        ownerSessionId = session,
        metadata = FileTransferMetadata(
            id = FileTransferId(id),
            displayName = "$id.txt",
            sizeBytes = 0,
            mimeType = "text/plain",
            sha256 = "a".repeat(64),
            direction = FileTransferDirection.BROWSER_TO_ANDROID,
        ),
    )

    private fun FileTransferState.started() = listOf(FileTransferEvent.Connecting, FileTransferEvent.Started)
        .fold(this, FileTransferReducer::reduce)

    private fun FileTransferState.completed() = listOf(FileTransferEvent.Verifying, FileTransferEvent.Completed)
        .fold(started(), FileTransferReducer::reduce)

    private companion object {
        val GENERATION = ServerGenerationId(1)
        val ORDINARY = BrowserSession(
            id = BrowserSessionId("ordinary"),
            generationId = GENERATION,
            browserLabel = "Edge",
            sourceIpv4 = "192.168.1.4",
            connectedAtElapsedRealtimeMs = 2,
        )
        val TRUSTED = BrowserSession(
            id = BrowserSessionId("trusted"),
            generationId = GENERATION,
            browserLabel = "Chrome",
            sourceIpv4 = "192.168.1.3",
            connectedAtElapsedRealtimeMs = 1,
            trustedBrowserId = TrustedBrowserId("trusted-browser"),
        )
    }
}
