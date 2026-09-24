package ru.hznik.devicebridge.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferEvent
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.FileTransferReducer
import ru.hznik.devicebridge.domain.file.FileTransferState
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.PairingChallengeId
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.session.PendingBrowserRequest
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferItem
import ru.hznik.devicebridge.server.EventNotificationCommand.Cancel
import ru.hznik.devicebridge.server.EventNotificationCommand.Show

class EventNotificationPlannerTest {

    private val planner = EventNotificationPlanner().apply {
        // Every test starts after the first, silent snapshot of a running server.
        plan(EventSnapshot(GENERATION, NOW), appVisible = false)
    }

    @Test
    fun firstSnapshotIsWhatAlreadyHappened() {
        val fresh = EventNotificationPlanner()

        val commands = fresh.plan(
            EventSnapshot(GENERATION, NOW, pendingRequests = listOf(request("r1")), texts = listOf(text("m1"))),
            appVisible = false,
        )

        assertTrue(commands.isEmpty())
    }

    @Test
    fun pairingRequestIsShownUntilItIsDecidedOrExpires() {
        val shown = planner.plan(snapshot(pendingRequests = listOf(request("r1"))), appVisible = false)
        val again = planner.plan(snapshot(pendingRequests = listOf(request("r1"))), appVisible = false)
        val gone = planner.plan(snapshot(), appVisible = false)

        val notice = (shown.single() as Show).notice as EventNotice.PairingRequest
        assertEquals("Chrome", notice.browserLabel)
        assertEquals(50_000L, notice.remainingMs)
        assertTrue(again.isEmpty())
        assertEquals(listOf(Cancel(EventNotificationKey.Pairing(PairingRequestId("r1")))), gone)
    }

    @Test
    fun nothingNewIsShownWhileTheAppIsVisibleAndItIsNotShownLater() {
        val visible = planner.plan(snapshot(texts = listOf(text("m1"))), appVisible = true)
        val hidden = planner.plan(snapshot(texts = listOf(text("m1"))), appVisible = false)

        assertTrue(visible.isEmpty())
        assertTrue(hidden.isEmpty())
    }

    @Test
    fun decidedRequestDisappearsEvenWhileTheAppIsVisible() {
        planner.plan(snapshot(pendingRequests = listOf(request("r1"))), appVisible = false)

        val decided = planner.plan(snapshot(), appVisible = true)

        assertEquals(listOf(Cancel(EventNotificationKey.Pairing(PairingRequestId("r1")))), decided)
    }

    @Test
    fun incomingTextAndLinkAreShownOnce() {
        val commands = planner.plan(
            snapshot(texts = listOf(text("m1"), text("m2", "https://example.com", TextContentKind.LINK))),
            appVisible = false,
        )
        val repeated = planner.plan(
            snapshot(texts = listOf(text("m1"), text("m2", "https://example.com", TextContentKind.LINK))),
            appVisible = false,
        )

        val notices = commands.map { (it as Show).notice as EventNotice.IncomingText }
        assertEquals(listOf(false, true), notices.map { it.isLink })
        assertTrue(repeated.isEmpty())
    }

    @Test
    fun serverStopRemovesTextRequestsAndOffersButKeepsResults() {
        planner.plan(snapshot(transfers = listOf(incoming("done").started())), appVisible = false)
        val result = planner.plan(snapshot(transfers = listOf(incoming("done").completed())), appVisible = false)
        assertTrue((result.single() as Show).notice is EventNotice.TransferResult)
        planner.plan(
            snapshot(
                pendingRequests = listOf(request("r1")),
                texts = listOf(text("m1")),
                transfers = listOf(incoming("t1").connecting()),
            ),
            appVisible = false,
        )

        val stopped = planner.plan(EventSnapshot(generationId = null, nowElapsedMs = NOW), appVisible = false)

        assertEquals(
            setOf(
                EventNotificationKey.Pairing(PairingRequestId("r1")),
                EventNotificationKey.Text(SESSION, TextMessageId("m1")),
                EventNotificationKey.IncomingFiles(SESSION),
            ),
            stopped.map { (it as Cancel).key }.toSet(),
        )
    }

    @Test
    fun offerOfThreeFilesIsOneNotificationThatFollowsTheOffer() {
        val queued = listOf(incoming("t1").connecting(), incoming("t2"), incoming("t3"))

        val shown = planner.plan(snapshot(transfers = queued, folderChosen = true), appVisible = false)
        val first = (shown.single() as Show)
        val notice = first.notice as EventNotice.IncomingFiles
        assertTrue(first.alert)
        assertEquals(3, notice.transferIds.size)
        assertEquals(30L, notice.totalBytes)
        assertTrue(notice.acceptable)

        // Accepting the first file updates the notification without a new sound.
        val updated = planner.plan(
            snapshot(
                transfers = listOf(incoming("t1").connecting().started(), incoming("t2").connecting(), incoming("t3")),
                folderChosen = true,
            ),
            appVisible = false,
        )
        val update = updated.single() as Show
        assertEquals(2, (update.notice as EventNotice.IncomingFiles).transferIds.size)
        assertTrue(!update.alert)

        val decided = planner.plan(
            snapshot(transfers = queued.map { it.cancelled() }, folderChosen = true),
            appVisible = false,
        )
        assertEquals(listOf(Cancel(EventNotificationKey.IncomingFiles(SESSION))), decided)
    }

    @Test
    fun offerWithoutAFolderOrWithAPausedFileCannotBeAcceptedFromTheNotification() {
        val noFolder = planner.plan(snapshot(transfers = listOf(incoming("t1").connecting())), appVisible = false)
        assertTrue(!((noFolder.single() as Show).notice as EventNotice.IncomingFiles).acceptable)

        val paused = EventNotificationPlanner().apply { plan(snapshot(), false) }.plan(
            snapshot(
                transfers = listOf(incoming("t1").connecting()),
                handledTransfers = setOf(FileTransferId("t1")),
                pausedTransfers = setOf(FileTransferId("t1")),
                folderChosen = true,
            ),
            appVisible = false,
        )
        assertTrue(!((paused.single() as Show).notice as EventNotice.IncomingFiles).acceptable)
    }

    @Test
    fun filesTheAppAcceptsByItselfDoNotAskButReportTheResult() {
        val handled = setOf(FileTransferId("t1"))
        val offered = planner.plan(
            snapshot(transfers = listOf(incoming("t1").connecting()), handledTransfers = handled),
            appVisible = false,
        )
        val done = planner.plan(
            snapshot(transfers = listOf(incoming("t1").completed()), handledTransfers = handled),
            appVisible = false,
        )

        assertTrue(offered.isEmpty())
        val result = (done.single() as Show).notice as EventNotice.TransferResult
        assertEquals(1, result.completed)
    }

    @Test
    fun resultWaitsForTheWholeGroupAndCountsFailures() {
        planner.plan(snapshot(transfers = listOf(incoming("t1").started(), incoming("t2").started())), false)

        val oneLeft = planner.plan(
            snapshot(transfers = listOf(incoming("t1").completed(), incoming("t2").started())),
            appVisible = false,
        )
        val finished = planner.plan(
            snapshot(transfers = listOf(incoming("t1").completed(), incoming("t2").failed())),
            appVisible = false,
        )
        val repeated = planner.plan(
            snapshot(transfers = listOf(incoming("t1").completed(), incoming("t2").failed())),
            appVisible = false,
        )

        assertTrue(oneLeft.isEmpty())
        val result = (finished.single() as Show).notice as EventNotice.TransferResult
        assertEquals(FileTransferDirection.BROWSER_TO_ANDROID, result.direction)
        assertEquals(1, result.completed)
        assertEquals(1, result.failed)
        assertTrue(repeated.isEmpty())
    }

    @Test
    fun retriedFileReportsAgainAndADeclinedOfferReportsNothing() {
        planner.plan(snapshot(transfers = listOf(incoming("t1").started())), false)
        planner.plan(snapshot(transfers = listOf(incoming("t1").failed())), false)
        planner.plan(snapshot(transfers = listOf(incoming("t1").started())), false)

        val retried = planner.plan(snapshot(transfers = listOf(incoming("t1").completed())), false)
        assertEquals(1, ((retried.single() as Show).notice as EventNotice.TransferResult).completed)

        planner.plan(snapshot(transfers = listOf(incoming("t2").connecting())), false)
        val declined = planner.plan(snapshot(transfers = listOf(incoming("t2").cancelled())), false)
        assertEquals(listOf(Cancel(EventNotificationKey.IncomingFiles(SESSION))), declined)
    }

    @Test
    fun anOfferLeftByItsBrowserBeforeStartingIsNotAFailure() {
        planner.plan(snapshot(transfers = listOf(incoming("t1").connecting(), incoming("t2"))), false)

        val gone = planner.plan(
            snapshot(transfers = listOf(incoming("t1").connecting().failed(), incoming("t2").failed())),
            appVisible = false,
        )

        assertEquals(listOf(Cancel(EventNotificationKey.IncomingFiles(SESSION))), gone)
    }

    @Test
    fun resultsAreSeparatePerDirection() {
        planner.plan(snapshot(transfers = listOf(incoming("t1").started(), outgoing("o1").started())), false)

        val done = planner.plan(
            snapshot(transfers = listOf(incoming("t1").completed(), outgoing("o1").delivered())),
            appVisible = false,
        )

        assertEquals(
            setOf(FileTransferDirection.BROWSER_TO_ANDROID, FileTransferDirection.ANDROID_TO_BROWSER),
            done.map { ((it as Show).notice as EventNotice.TransferResult).direction }.toSet(),
        )
    }

    private fun snapshot(
        pendingRequests: List<PendingBrowserRequest> = emptyList(),
        texts: List<TextTransferItem> = emptyList(),
        transfers: List<FileTransferState> = emptyList(),
        handledTransfers: Set<FileTransferId> = emptySet(),
        pausedTransfers: Set<FileTransferId> = emptySet(),
        folderChosen: Boolean = false,
    ) = EventSnapshot(
        generationId = GENERATION,
        nowElapsedMs = NOW,
        pendingRequests = pendingRequests,
        transfers = transfers,
        texts = texts,
        handledTransfers = handledTransfers,
        pausedTransfers = pausedTransfers,
        folderChosen = folderChosen,
    )

    private fun request(id: String) = PendingBrowserRequest(
        id = PairingRequestId(id),
        challengeId = PairingChallengeId("c-$id"),
        generationId = GENERATION,
        browserLabel = "Chrome",
        sourceIpv4 = "192.168.1.3",
        createdAtElapsedRealtimeMs = NOW - 10_000,
        expiresAtElapsedRealtimeMs = NOW + 50_000,
    )

    private fun text(id: String, content: String = "Привет", kind: TextContentKind = TextContentKind.TEXT) =
        TextTransferItem.incoming(
            id = TextMessageId(id),
            generationId = GENERATION,
            sessionId = SESSION,
            browserLabel = "Chrome",
            content = content,
            contentKind = kind,
            receivedAtEpochMillis = 1_000,
        )

    private fun incoming(id: String) = transfer(id, FileTransferDirection.BROWSER_TO_ANDROID, size = 10)

    private fun outgoing(id: String) = transfer(id, FileTransferDirection.ANDROID_TO_BROWSER, size = 0)

    private fun transfer(id: String, direction: FileTransferDirection, size: Long) = FileTransferState.queued(
        generationId = GENERATION,
        ownerSessionId = SESSION,
        metadata = FileTransferMetadata(
            id = FileTransferId(id),
            displayName = "$id.txt",
            sizeBytes = size,
            mimeType = "text/plain",
            sha256 = "a".repeat(64),
            direction = direction,
        ),
    )

    private fun FileTransferState.on(vararg events: FileTransferEvent) =
        events.fold(this) { state, event -> FileTransferReducer.reduce(state, event) }

    private fun FileTransferState.connecting() = on(FileTransferEvent.Connecting)

    private fun FileTransferState.started() =
        if (phase == FileTransferPhase.QUEUED) on(FileTransferEvent.Connecting, FileTransferEvent.Started) else on(FileTransferEvent.Started)

    private fun FileTransferState.completed() = started().on(
        FileTransferEvent.Progressed(metadata.sizeBytes, 0),
        FileTransferEvent.Verifying,
        FileTransferEvent.Completed,
    )

    private fun FileTransferState.delivered() =
        started().on(FileTransferEvent.Progressed(metadata.sizeBytes, 0), FileTransferEvent.Delivered)

    private fun FileTransferState.failed() = on(FileTransferEvent.Failed(FileTransferFailure.StreamFailed))

    private fun FileTransferState.cancelled() = on(FileTransferEvent.Cancelled)

    private companion object {
        val GENERATION = ServerGenerationId(1)
        val SESSION = BrowserSessionId("session-1")
        const val NOW = 100_000L
    }
}
