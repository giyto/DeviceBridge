package ru.hznik.devicebridge.feature.text

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository
import ru.hznik.devicebridge.domain.repository.TextTransferRepository
import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingCodeState
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.text.SendTextRequest
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferFailureReason
import ru.hznik.devicebridge.domain.text.TextTransferItem
import ru.hznik.devicebridge.domain.text.TextTransferRejection
import ru.hznik.devicebridge.domain.text.TextTransferResult
import ru.hznik.devicebridge.domain.text.TextTransferState
import ru.hznik.devicebridge.domain.text.TextTransferStatus
import ru.hznik.devicebridge.domain.usecase.ObserveBrowserSessionsUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveTextTransfersUseCase
import ru.hznik.devicebridge.domain.usecase.RetryTextTransferUseCase
import ru.hznik.devicebridge.domain.usecase.SendTextToBrowserUseCase

@OptIn(ExperimentalCoroutinesApi::class)
class TextViewModelTest {
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun oneSessionIsSelectedAndDraftProducesSafePreview() = runTest(dispatcher) {
        val sessions = FakeBrowserSessions(browserState(firstSession))
        val transfers = FakeTextTransfers()
        val viewModel = createViewModel(sessions, transfers)
        runCurrent()

        viewModel.onAction(TextAction.DraftChanged("https://example.com/path"))
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(firstSession.id, state.selectedSessionId)
        assertEquals(TextContentKind.LINK, state.preview?.contentKind)
        assertEquals("https://example.com/path", state.preview?.content)
        assertTrue(state.canSend)
    }

    @Test
    fun severalSessionsRequireExplicitSingleRecipient() = runTest(dispatcher) {
        val sessions = FakeBrowserSessions(browserState(firstSession, secondSession))
        val viewModel = createViewModel(sessions, FakeTextTransfers())
        runCurrent()

        viewModel.onAction(TextAction.DraftChanged("hello"))
        runCurrent()
        assertNull(viewModel.uiState.value.selectedSessionId)
        assertTrue(viewModel.uiState.value.recipientSelectionRequired)
        assertFalse(viewModel.uiState.value.canSend)

        viewModel.onAction(TextAction.RecipientSelected(secondSession.id))
        runCurrent()
        assertEquals(secondSession.id, viewModel.uiState.value.selectedSessionId)
        assertTrue(viewModel.uiState.value.canSend)
    }

    @Test
    fun sharedTextBecomesUnconfirmedDraftAndNeverSendsAutomatically() =
        runTest(dispatcher) {
            val transfers = FakeTextTransfers()
            val viewModel = createViewModel(
                FakeBrowserSessions(browserState(firstSession)),
                transfers,
            )
            runCurrent()

            viewModel.onAction(TextAction.SharedDraftReceived("shared note"))
            runCurrent()

            val pending = viewModel.uiState.value
            assertEquals("shared note", pending.draft)
            assertNull(pending.selectedSessionId)
            assertTrue(pending.recipientSelectionRequired)
            assertFalse(pending.canSend)
            assertTrue(transfers.sent.isEmpty())

            viewModel.onAction(TextAction.RecipientSelected(firstSession.id))
            runCurrent()
            assertTrue(viewModel.uiState.value.canSend)
            assertTrue(transfers.sent.isEmpty())
        }

    @Test
    fun disconnectedRecipientIsNotSilentlyReplaced() = runTest(dispatcher) {
        val sessions = FakeBrowserSessions(browserState(firstSession, secondSession))
        val viewModel = createViewModel(sessions, FakeTextTransfers())
        viewModel.onAction(TextAction.RecipientSelected(secondSession.id))
        runCurrent()

        sessions.mutableState.value = browserState(firstSession)
        runCurrent()

        val state = viewModel.uiState.value
        assertNull(state.selectedSessionId)
        assertTrue(state.recipientSelectionRequired)
        assertFalse(state.canSend)
        assertEquals("Выбранный браузер отключён. Выберите получателя.", state.errorMessage)
    }

    @Test
    fun successfulSendUsesSelectedSessionClearsDraftAndShowsFeed() = runTest(dispatcher) {
        val sessions = FakeBrowserSessions(browserState(firstSession))
        val savedState = SavedStateHandle()
        val delivered = outgoingItem(
            id = "outgoing-1",
            session = firstSession,
            content = "hello",
            status = TextTransferStatus.DELIVERED,
        )
        val transfers = FakeTextTransfers(
            sendResult = TextTransferResult.Accepted(delivered),
        )
        val viewModel = createViewModel(sessions, transfers, savedState)
        viewModel.onAction(TextAction.DraftChanged("hello"))
        runCurrent()

        viewModel.onAction(TextAction.SendClicked)
        runCurrent()
        transfers.mutableState.value = TextTransferState.of(listOf(delivered))
        runCurrent()

        assertEquals(listOf(SendTextRequest(firstSession.id, "hello")), transfers.sent)
        assertEquals("", viewModel.uiState.value.draft)
        assertEquals("Текст доставлен.", viewModel.uiState.value.successMessage)
        assertEquals(TextTransferStatus.DELIVERED, viewModel.uiState.value.items.single().status)

        val recreated = createViewModel(sessions, transfers, savedState)
        runCurrent()
        assertEquals("", recreated.uiState.value.draft)
    }

    @Test
    fun rejectedSendKeepsDraftAndExplainsRecoverableError() = runTest(dispatcher) {
        val transfers = FakeTextTransfers(
            sendResult = TextTransferResult.Rejected(TextTransferRejection.SESSION_UNAVAILABLE),
        )
        val viewModel = createViewModel(
            FakeBrowserSessions(browserState(firstSession)),
            transfers,
        )
        viewModel.onAction(TextAction.DraftChanged("keep me"))
        runCurrent()

        viewModel.onAction(TextAction.SendClicked)
        runCurrent()

        assertEquals("keep me", viewModel.uiState.value.draft)
        assertEquals(
            "Получатель недоступен. Выберите активный браузер.",
            viewModel.uiState.value.errorMessage,
        )
        assertFalse(viewModel.uiState.value.isSending)
    }

    @Test
    fun unavailableBrowserChannelKeepsDraftAndExplainsVpnRecovery() = runTest(dispatcher) {
        val failed = outgoingItem(
            id = "outgoing-1",
            session = firstSession,
            content = "hello",
            status = TextTransferStatus.FAILED,
            failureReason = TextTransferFailureReason.SESSION_CLOSED,
        )
        val transfers = FakeTextTransfers(
            sendResult = TextTransferResult.Accepted(failed),
        )
        val viewModel = createViewModel(
            FakeBrowserSessions(browserState(firstSession)),
            transfers,
        )
        viewModel.onAction(TextAction.DraftChanged("hello"))
        runCurrent()

        viewModel.onAction(TextAction.SendClicked)
        runCurrent()

        assertEquals("hello", viewModel.uiState.value.draft)
        assertEquals(
            "Браузер не подключён. Обновите страницу и проверьте доступ VPN к локальной сети.",
            viewModel.uiState.value.errorMessage,
        )
    }

    @Test
    fun failedItemCanBeRetriedWithSameMessageId() = runTest(dispatcher) {
        val failed = outgoingItem(
            id = "retry-1",
            session = firstSession,
            content = "retry",
            status = TextTransferStatus.FAILED,
        )
        val delivered = outgoingItem(
            id = "retry-1",
            session = firstSession,
            content = "retry",
            status = TextTransferStatus.DELIVERED,
        )
        val transfers = FakeTextTransfers(
            initial = TextTransferState.of(listOf(failed)),
            retryResult = TextTransferResult.Accepted(delivered),
        )
        val viewModel = createViewModel(
            FakeBrowserSessions(browserState(firstSession)),
            transfers,
        )
        runCurrent()

        viewModel.onAction(TextAction.RetryClicked(TextMessageId("retry-1")))
        runCurrent()

        assertEquals(listOf(TextMessageId("retry-1")), transfers.retried)
        assertEquals("Повторная отправка выполнена.", viewModel.uiState.value.successMessage)
    }

    @Test
    fun protocolFailureDoesNotOfferOrExecuteUnchangedRetry() = runTest(dispatcher) {
        val failed = outgoingItem(
            id = "protocol-failure",
            session = firstSession,
            content = "retry",
            status = TextTransferStatus.FAILED,
            failureReason = TextTransferFailureReason.PROTOCOL_ERROR,
        )
        val transfers = FakeTextTransfers(initial = TextTransferState.of(listOf(failed)))
        val viewModel = createViewModel(
            FakeBrowserSessions(browserState(firstSession)),
            transfers,
        )
        runCurrent()

        assertFalse(viewModel.uiState.value.items.single().canRetry)
        viewModel.onAction(TextAction.RetryClicked(TextMessageId("protocol-failure")))
        runCurrent()
        assertTrue(transfers.retried.isEmpty())
    }
    @Test
    fun draftSurvivesRecreationOnlyInsideTheCurrentServerGeneration() = runTest(dispatcher) {
        val savedState = SavedStateHandle()
        val sessions = FakeBrowserSessions(browserState(firstSession))
        val first = createViewModel(sessions, FakeTextTransfers(), savedState)
        runCurrent()

        first.onAction(TextAction.DraftChanged("Черновик между recreation"))
        runCurrent()
        val recreated = createViewModel(sessions, FakeTextTransfers(), savedState)
        runCurrent()
        assertEquals("Черновик между recreation", recreated.uiState.value.draft)

        recreated.onAction(TextAction.DraftChanged(""))
        runCurrent()
        val afterDiscard = createViewModel(sessions, FakeTextTransfers(), savedState)
        runCurrent()
        assertEquals("", afterDiscard.uiState.value.draft)

        recreated.onAction(TextAction.DraftChanged("Черновик старого generation"))
        runCurrent()
        sessions.mutableState.value = BrowserSessionState.active(
            generationId = ServerGenerationId(2),
            pairingCode = PairingCodeState("654321", 60_000),
        )
        runCurrent()
        assertEquals("", recreated.uiState.value.draft)
        assertEquals("", afterDiscard.uiState.value.draft)

        recreated.onAction(TextAction.DraftChanged("Очистить при остановке"))
        runCurrent()
        sessions.mutableState.value = BrowserSessionState.inactive()
        runCurrent()
        assertEquals("", recreated.uiState.value.draft)
        assertEquals("", afterDiscard.uiState.value.draft)
    }
    private fun createViewModel(
        sessions: FakeBrowserSessions,
        transfers: FakeTextTransfers,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) = TextViewModel(
        observeBrowserSessions = ObserveBrowserSessionsUseCase(sessions),
        observeTextTransfers = ObserveTextTransfersUseCase(transfers),
        sendText = SendTextToBrowserUseCase(transfers),
        retryText = RetryTextTransferUseCase(transfers),
        savedStateHandle = savedStateHandle,
    )

    private class FakeBrowserSessions(
        initial: BrowserSessionState,
    ) : BrowserSessionRepository {
        val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<BrowserSessionState> = mutableState

        override suspend fun approve(requestId: PairingRequestId) = Unit
        override suspend fun deny(requestId: PairingRequestId) = Unit
        override suspend fun revoke(sessionId: BrowserSessionId) = Unit
    }

    private class FakeTextTransfers(
        initial: TextTransferState = TextTransferState.empty(),
        var sendResult: TextTransferResult =
            TextTransferResult.Rejected(TextTransferRejection.SESSION_UNAVAILABLE),
        var retryResult: TextTransferResult =
            TextTransferResult.Rejected(TextTransferRejection.MESSAGE_NOT_FOUND),
    ) : TextTransferRepository {
        val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<TextTransferState> = mutableState
        val sent = mutableListOf<SendTextRequest>()
        val retried = mutableListOf<TextMessageId>()

        override suspend fun send(request: SendTextRequest): TextTransferResult {
            sent += request
            return sendResult
        }

        override suspend fun receive(
            request: ru.hznik.devicebridge.domain.text.IncomingTextRequest,
        ): TextTransferResult = error("Not used")

        override suspend fun retry(messageId: TextMessageId): TextTransferResult {
            retried += messageId
            return retryResult
        }
    }

    companion object {
        private val generationId = ServerGenerationId(1)
        private val firstSession = session("session-1", "Chrome", "192.168.1.2")
        private val secondSession = session("session-2", "Яндекс Браузер", "192.168.1.3")

        private fun browserState(vararg sessions: BrowserSession) = BrowserSessionState.active(
            generationId = generationId,
            pairingCode = PairingCodeState("123456", 60_000),
            sessions = sessions.toList(),
        )

        private fun session(id: String, label: String, address: String) = BrowserSession(
            id = BrowserSessionId(id),
            generationId = generationId,
            browserLabel = label,
            sourceIpv4 = address,
            connectedAtElapsedRealtimeMs = 1_000,
        )

        private fun outgoingItem(
            id: String,
            session: BrowserSession,
            content: String,
            status: TextTransferStatus,
            failureReason: TextTransferFailureReason = TextTransferFailureReason.CONNECTION_LOST,
        ): TextTransferItem {
            val pending = TextTransferItem.outgoing(
                id = TextMessageId(id),
                generationId = generationId,
                sessionId = session.id,
                browserLabel = session.browserLabel,
                content = content,
                contentKind = TextContentKind.TEXT,
                createdAtEpochMillis = 1_000_000,
            )
            val sending = pending.transitionTo(TextTransferStatus.SENDING, 1_000_001)
            return when (status) {
                TextTransferStatus.PENDING -> pending
                TextTransferStatus.SENDING -> sending
                TextTransferStatus.DELIVERED ->
                    sending.transitionTo(TextTransferStatus.DELIVERED, 1_000_002)
                TextTransferStatus.FAILED -> sending.transitionTo(
                    next = TextTransferStatus.FAILED,
                    changedAtEpochMillis = 1_000_002,
                    failureReason = failureReason,
                )
            }
        }
    }
}
