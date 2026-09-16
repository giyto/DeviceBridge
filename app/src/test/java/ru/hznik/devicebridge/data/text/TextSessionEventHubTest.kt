package ru.hznik.devicebridge.data.text

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.session.SessionEventDispatcher
import ru.hznik.devicebridge.data.session.SessionOutboundEvent
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferItem

@OptIn(ExperimentalCoroutinesApi::class)
class TextSessionEventHubTest {

    @Test
    fun deliveryWaitsForAuthorizedBrowserChannelToAttach() = runTest {
        val hub = SessionEventDispatcher(scope = backgroundScope)
        var deliveredItem: TextTransferItem? = null
        val delivery = async { hub.deliver(outgoingItem()) }
        runCurrent()

        assertFalse(delivery.isCompleted)

        hub.attach(sessionId) { event ->
            deliveredItem = (event as SessionOutboundEvent.Text).item
            true
        }
        runCurrent()

        assertTrue(delivery.await())
        assertTrue(deliveredItem != null)
    }

    @Test
    fun deliveryStopsWaitingWhenBrowserChannelNeverAttaches() = runTest {
        val hub = SessionEventDispatcher(scope = backgroundScope)
        val delivery = async { hub.deliver(outgoingItem()) }
        runCurrent()

        assertFalse(delivery.isCompleted)

        advanceTimeBy(2_001)
        runCurrent()

        assertFalse(delivery.await())
    }

    private fun outgoingItem() = TextTransferItem.outgoing(
        id = TextMessageId("message-1"),
        generationId = ServerGenerationId(1),
        sessionId = sessionId,
        browserLabel = "Browser",
        content = "hello",
        contentKind = TextContentKind.TEXT,
        createdAtEpochMillis = 1_000,
    )

    private companion object {
        val sessionId = BrowserSessionId("session-1")
    }
}
