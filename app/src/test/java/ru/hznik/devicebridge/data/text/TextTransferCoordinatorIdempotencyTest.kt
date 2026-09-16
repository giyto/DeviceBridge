package ru.hznik.devicebridge.data.text

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.text.IncomingTextRequest
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferRejection
import ru.hznik.devicebridge.domain.text.TextTransferResult

class TextTransferCoordinatorIdempotencyTest {

    private val generationId = ServerGenerationId(7)
    private val sessionId = BrowserSessionId("session-1")

    @Test
    fun firstIncomingMessageIsAcceptedAndUsesServerTimeAndClassification() = runTest {
        val coordinator = TextTransferCoordinator(nowEpochMillis = { 5_000 })
        coordinator.activate(generationId)

        val result = coordinator.acceptIncoming(request(content = "https://example.com"))

        val item = (result as TextTransferResult.Accepted).item
        assertEquals(TextContentKind.LINK, item.contentKind)
        assertEquals(5_000, item.createdAtEpochMillis)
        assertEquals(listOf(item), coordinator.state.value.items)
    }

    @Test
    fun repeatedMessageWithSamePayloadReturnsOriginalOutcomeWithoutDuplicate() = runTest {
        val coordinator = TextTransferCoordinator(nowEpochMillis = { 5_000 })
        coordinator.activate(generationId)
        val request = request(content = "hello")

        val first = coordinator.acceptIncoming(request)
        val repeated = coordinator.acceptIncoming(request.copy(requestedAtEpochMillis = 9_000))

        assertSame(first, repeated)
        assertEquals(1, coordinator.state.value.items.size)
    }

    @Test
    fun repeatedMessageIdWithDifferentPayloadIsRejectedAsConflict() = runTest {
        val coordinator = TextTransferCoordinator(nowEpochMillis = { 5_000 })
        coordinator.activate(generationId)
        val request = request(content = "first")
        coordinator.acceptIncoming(request)

        val result = coordinator.acceptIncoming(request.copy(content = "different"))

        assertEquals(
            TextTransferResult.Rejected(TextTransferRejection.MESSAGE_CONFLICT),
            result,
        )
        assertEquals(listOf("first"), coordinator.state.value.items.map { it.content })
    }

    @Test
    fun inactiveOrStaleGenerationCannotAcceptContent() = runTest {
        val coordinator = TextTransferCoordinator(nowEpochMillis = { 5_000 })

        assertEquals(
            TextTransferResult.Rejected(TextTransferRejection.GENERATION_CLOSED),
            coordinator.acceptIncoming(request()),
        )

        coordinator.activate(ServerGenerationId(8))
        assertEquals(
            TextTransferResult.Rejected(TextTransferRejection.GENERATION_CLOSED),
            coordinator.acceptIncoming(request()),
        )
    }

    private fun request(content: String = "hello") = IncomingTextRequest(
        id = TextMessageId("message-1"),
        generationId = generationId,
        sessionId = sessionId,
        browserLabel = "Яндекс Браузер на Windows",
        content = content,
        requestedAtEpochMillis = 1_000,
    )
}
