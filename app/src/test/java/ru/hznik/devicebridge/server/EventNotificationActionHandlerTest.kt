package ru.hznik.devicebridge.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.file.NotificationAcceptedTransfers
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferEvent
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferReducer
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.file.FileTransferState
import ru.hznik.devicebridge.domain.file.VerifyFileTransferRequest
import ru.hznik.devicebridge.domain.repository.FileTransferRepository
import ru.hznik.devicebridge.domain.repository.TextTransferRepository
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository
import ru.hznik.devicebridge.domain.usecase.ApproveBrowserRequestUseCase
import ru.hznik.devicebridge.domain.usecase.DenyBrowserRequestUseCase
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.text.IncomingTextRequest
import ru.hznik.devicebridge.domain.text.SendTextRequest
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferItem
import ru.hznik.devicebridge.domain.text.TextTransferState

class EventNotificationActionHandlerTest {

    private val registry = EventNotificationRegistry()
    private val publisher = RecordingPublisher()
    private val texts = FakeTexts()
    private val files = FakeFiles()
    private val accepted = NotificationAcceptedTransfers()
    private val copied = mutableListOf<String>()
    private val sessions = RecordingSessions()
    private val handler = EventNotificationActionHandler(
        registry = registry,
        publisher = publisher,
        texts = texts,
        files = files,
        notificationAccepted = accepted,
        clipboard = { copied += it },
        approveRequest = ApproveBrowserRequestUseCase(sessions),
        denyRequest = DenyBrowserRequestUseCase(sessions),
    )

    @Test
    fun pairingButtonsMakeTheSameDecisionsAsTheApp() = runTest {
        val request = PairingRequestId("request-1")
        val notice = EventNotice.PairingRequest(request, "Edge, Windows", 45_000, rememberRequested = true)

        handler.handle(EventNotificationAction.ALLOW_PAIRING, registry.register(notice))
        handler.handle(EventNotificationAction.ALLOW_AND_REMEMBER_PAIRING, registry.register(notice))
        handler.handle(EventNotificationAction.DENY_PAIRING, registry.register(notice))

        // "Разрешить" never remembers the browser, even when it asked to be remembered.
        assertEquals(listOf("approve:request-1", "remember:request-1", "deny:request-1"), sessions.calls)
        assertEquals(listOf(notice.key, notice.key, notice.key), publisher.cancelled)
    }

    @Test
    fun aRequestThatIsNoLongerPendingOnlyLosesItsNotification() = runTest {
        sessions.failing = true
        val notice = EventNotice.PairingRequest(PairingRequestId("gone"), "Edge, Windows", 45_000)

        handler.handle(EventNotificationAction.ALLOW_PAIRING, registry.register(notice))

        assertEquals(listOf(notice.key), publisher.cancelled)
    }

    @Test
    fun pairingButtonOnAnotherKindOfNotificationDoesNothing() = runTest {
        val textId = registry.register(EventNotice.IncomingText(SESSION, TextMessageId("m1"), "x", false))

        handler.handle(EventNotificationAction.ALLOW_PAIRING, textId)

        assertTrue(sessions.calls.isEmpty())
        assertTrue(publisher.cancelled.isEmpty())
    }

    private class RecordingSessions : BrowserSessionRepository {
        val calls = mutableListOf<String>()
        var failing = false
        override val state = MutableStateFlow(BrowserSessionState.inactive())
        override val connectedSessionIds = MutableStateFlow(emptySet<BrowserSessionId>())

        override suspend fun approve(requestId: PairingRequestId) = record("approve", requestId)

        override suspend fun approveAndRemember(requestId: PairingRequestId) = record("remember", requestId)

        override suspend fun deny(requestId: PairingRequestId) = record("deny", requestId)

        override suspend fun revoke(sessionId: BrowserSessionId) = Unit

        private fun record(kind: String, requestId: PairingRequestId) {
            if (failing) error("The request is no longer pending")
            calls += "$kind:${requestId.value}"
        }
    }

    @Test
    fun copyPutsTheWholeTextOnTheClipboardAndClosesTheNotification() = runTest {
        texts.state.value = TextTransferState.of(listOf(text("m1", "Полный текст")))
        val notice = EventNotice.IncomingText(SESSION, TextMessageId("m1"), "Полный", false)
        val id = registry.register(notice)

        handler.handle(EventNotificationAction.COPY_TEXT, id)

        assertEquals(listOf("Полный текст"), copied)
        assertEquals(listOf(notice.key), publisher.cancelled)
    }

    @Test
    fun copyOfATextThatIsGoneOnlyClosesTheNotification() = runTest {
        val notice = EventNotice.IncomingText(SESSION, TextMessageId("m1"), "Полный", false)
        val id = registry.register(notice)

        handler.handle(EventNotificationAction.COPY_TEXT, id)

        assertTrue(copied.isEmpty())
        assertEquals(listOf(notice.key), publisher.cancelled)
    }

    @Test
    fun acceptTakesEveryFileOfTheOfferThatHasNotStarted() = runTest {
        files.state.value = FileTransferSnapshot(
            listOf(
                incoming("t1").started(),
                incoming("t2").connecting(),
                incoming("t3"),
                incoming("other", BrowserSessionId("session-2")),
            ),
        )
        val id = registry.register(offer())

        handler.handle(EventNotificationAction.ACCEPT_FILES, id)

        assertEquals(setOf(FileTransferId("t2"), FileTransferId("t3")), accepted.ids.value)
    }

    @Test
    fun declineCancelsOnlyFilesThatHaveNotStarted() = runTest {
        files.state.value = FileTransferSnapshot(
            listOf(incoming("t1").started(), incoming("t2").connecting(), incoming("t3")),
        )
        val id = registry.register(offer())

        handler.handle(EventNotificationAction.DECLINE_FILES, id)

        assertEquals(listOf(FileTransferId("t2"), FileTransferId("t3")), files.cancelled)
    }

    @Test
    fun unknownOrMismatchedNotificationDoesNothing() = runTest {
        val textId = registry.register(EventNotice.IncomingText(SESSION, TextMessageId("m1"), "x", false))

        handler.handle(EventNotificationAction.ACCEPT_FILES, textId)
        handler.handle(EventNotificationAction.COPY_TEXT, 12_345)

        assertTrue(accepted.ids.value.isEmpty())
        assertTrue(copied.isEmpty())
        assertTrue(publisher.cancelled.isEmpty())
    }

    private fun offer() = EventNotice.IncomingFiles(
        sessionId = SESSION,
        transferIds = listOf(FileTransferId("t2"), FileTransferId("t3")),
        firstName = "t2.txt",
        totalBytes = 20,
        acceptable = true,
    )

    private fun text(id: String, content: String) = TextTransferItem.incoming(
        id = TextMessageId(id),
        generationId = GENERATION,
        sessionId = SESSION,
        browserLabel = "Chrome",
        content = content,
        contentKind = TextContentKind.TEXT,
        receivedAtEpochMillis = 1_000,
    )

    private fun incoming(id: String, session: BrowserSessionId = SESSION) = FileTransferState.queued(
        generationId = GENERATION,
        ownerSessionId = session,
        metadata = FileTransferMetadata(
            id = FileTransferId(id),
            displayName = "$id.txt",
            sizeBytes = 10,
            mimeType = "text/plain",
            sha256 = "a".repeat(64),
            direction = FileTransferDirection.BROWSER_TO_ANDROID,
        ),
    )

    private fun FileTransferState.connecting() = FileTransferReducer.reduce(this, FileTransferEvent.Connecting)

    private fun FileTransferState.started() =
        FileTransferReducer.reduce(connecting(), FileTransferEvent.Started)

    private class RecordingPublisher : EventNotificationPublisher {
        val cancelled = mutableListOf<EventNotificationKey>()
        override fun show(notice: EventNotice, alert: Boolean) = Unit
        override fun cancel(key: EventNotificationKey) {
            cancelled += key
        }
    }

    private class FakeTexts : TextTransferRepository {
        override val state = MutableStateFlow(TextTransferState.empty())
        override suspend fun send(request: SendTextRequest) = error("Not used")
        override suspend fun receive(request: IncomingTextRequest) = error("Not used")
        override suspend fun retry(messageId: TextMessageId) = error("Not used")
    }

    private class FakeFiles : FileTransferRepository {
        override val state = MutableStateFlow(FileTransferSnapshot(emptyList()))
        val cancelled = mutableListOf<FileTransferId>()

        override suspend fun cancel(transferId: FileTransferId): FileTransferOperationResult {
            cancelled += transferId
            return FileTransferOperationResult.Accepted
        }

        override suspend fun create(request: CreateFileTransfersRequest) = error("Not used")
        override suspend fun approve(transferId: FileTransferId, destinationId: FileDestinationId?) =
            error("Not used")
        override suspend fun retry(transferId: FileTransferId) = error("Not used")
        override suspend fun verify(request: VerifyFileTransferRequest) = error("Not used")
    }

    private companion object {
        val GENERATION = ServerGenerationId(1)
        val SESSION = BrowserSessionId("session-1")
    }
}
