package ru.hznik.devicebridge.data.text

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.text.IncomingTextRequest
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferItem

class TextTransferCoordinatorBoundsTest {

    private val generationId = ServerGenerationId(7)
    private val sessionId = BrowserSessionId("session-1")

    @Test
    fun feedKeepsOnlyNewestCompletedItemsWithinConfiguredBound() = runTest {
        var now = 1_000L
        val coordinator = TextTransferCoordinator(
            nowEpochMillis = { now++ },
            maxFeedItems = 3,
        )
        coordinator.activate(generationId)

        repeat(5) { index ->
            coordinator.acceptIncoming(request(index + 1))
        }

        assertEquals(
            listOf("message-3", "message-4", "message-5"),
            coordinator.state.value.items.map { it.id.value },
        )
    }

    @Test
    fun feedPolicyNeverEvictsPendingItemToReachCompletedItemBound() {
        val pending = TextTransferItem.outgoing(
            id = TextMessageId("pending"),
            generationId = generationId,
            sessionId = sessionId,
            browserLabel = "Chrome",
            content = "pending",
            contentKind = TextContentKind.TEXT,
            createdAtEpochMillis = 1_000,
        )
        val firstCompleted = incomingItem("done-1", 1_100)
        val secondCompleted = incomingItem("done-2", 1_200)

        val retained = TextFeedPolicy.retainBounded(
            items = listOf(pending, firstCompleted, secondCompleted),
            maxCompletedItems = 1,
        )

        assertTrue(pending in retained)
        assertEquals(listOf("pending", "done-2"), retained.map { it.id.value })
    }

    @Test
    fun closingOrReplacingGenerationClearsFeedAndOutcomes() = runTest {
        val coordinator = TextTransferCoordinator(nowEpochMillis = { 2_000 })
        coordinator.activate(generationId)
        coordinator.acceptIncoming(request(1))

        coordinator.close(generationId)
        assertTrue(coordinator.state.value.items.isEmpty())

        coordinator.activate(ServerGenerationId(8))
        assertTrue(coordinator.state.value.items.isEmpty())
    }

    @Test
    fun sameMessageIdFromDifferentSessionsRemainsTwoDistinctItems() = runTest {
        val coordinator = TextTransferCoordinator(nowEpochMillis = { 2_000 })
        coordinator.activate(generationId)

        coordinator.acceptIncoming(request(1, BrowserSessionId("session-1")))
        coordinator.acceptIncoming(request(1, BrowserSessionId("session-2")))

        assertEquals(2, coordinator.state.value.items.size)
    }

    private fun request(
        number: Int,
        browserSessionId: BrowserSessionId = sessionId,
    ) = IncomingTextRequest(
        id = TextMessageId("message-$number"),
        generationId = generationId,
        sessionId = browserSessionId,
        browserLabel = "Chrome",
        content = "content-$number",
        requestedAtEpochMillis = 500,
    )

    private fun incomingItem(id: String, timestamp: Long) = TextTransferItem.incoming(
        id = TextMessageId(id),
        generationId = generationId,
        sessionId = sessionId,
        browserLabel = "Chrome",
        content = id,
        contentKind = TextContentKind.TEXT,
        receivedAtEpochMillis = timestamp,
    )
}
