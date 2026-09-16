package ru.hznik.devicebridge.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import ru.hznik.devicebridge.domain.repository.TextTransferRepository
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.text.IncomingTextRequest
import ru.hznik.devicebridge.domain.text.SendTextRequest
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferRejection
import ru.hznik.devicebridge.domain.text.TextTransferResult
import ru.hznik.devicebridge.domain.text.TextTransferState

class TextTransferUseCasesTest {

    @Test
    fun observeReturnsRepositoryStateFlow() {
        val repository = FakeTextTransferRepository()

        assertSame(repository.state, ObserveTextTransfersUseCase(repository)())
    }

    @Test
    fun actionsDelegateTypedRequestsToRepository() = runBlocking {
        val repository = FakeTextTransferRepository()
        val send = SendTextRequest(
            sessionId = BrowserSessionId("session-1"),
            content = "hello",
        )
        val incoming = IncomingTextRequest(
            id = TextMessageId("message-2"),
            generationId = ServerGenerationId(7),
            sessionId = BrowserSessionId("session-2"),
            browserLabel = "Edge on Windows",
            content = "from browser",
            requestedAtEpochMillis = 2_000,
        )
        val retryId = TextMessageId("message-3")

        SendTextToBrowserUseCase(repository)(send)
        ReceiveTextFromBrowserUseCase(repository)(incoming)
        RetryTextTransferUseCase(repository)(retryId)

        assertEquals(listOf(send), repository.sent)
        assertEquals(listOf(incoming), repository.received)
        assertEquals(listOf(retryId), repository.retried)
    }

    private class FakeTextTransferRepository : TextTransferRepository {
        override val state: StateFlow<TextTransferState> =
            MutableStateFlow(TextTransferState.empty())
        val sent = mutableListOf<SendTextRequest>()
        val received = mutableListOf<IncomingTextRequest>()
        val retried = mutableListOf<TextMessageId>()

        override suspend fun send(request: SendTextRequest): TextTransferResult {
            sent += request
            return TextTransferResult.Rejected(TextTransferRejection.SESSION_UNAVAILABLE)
        }

        override suspend fun receive(request: IncomingTextRequest): TextTransferResult {
            received += request
            return TextTransferResult.Rejected(TextTransferRejection.SESSION_UNAVAILABLE)
        }

        override suspend fun retry(messageId: TextMessageId): TextTransferResult {
            retried += messageId
            return TextTransferResult.Rejected(TextTransferRejection.MESSAGE_NOT_FOUND)
        }
    }
}
