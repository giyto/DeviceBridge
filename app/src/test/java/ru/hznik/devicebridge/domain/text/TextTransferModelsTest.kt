package ru.hznik.devicebridge.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId

class TextTransferModelsTest {

    private val generationId = ServerGenerationId(7)
    private val sessionId = BrowserSessionId("session-1")

    @Test
    fun outgoingItemKeepsSelectedBrowserAndStartsPending() {
        val item = TextTransferItem.outgoing(
            id = TextMessageId("message-1"),
            generationId = generationId,
            sessionId = sessionId,
            browserLabel = "Яндекс Браузер на Windows",
            content = "Привет",
            contentKind = TextContentKind.TEXT,
            createdAtEpochMillis = 1_000,
        )

        assertEquals(TextTransferDirection.ANDROID_TO_BROWSER, item.direction)
        assertEquals(TextTransferStatus.PENDING, item.status)
        assertEquals(sessionId, item.sessionId)
        assertEquals("Яндекс Браузер на Windows", item.browserLabel)
        assertNull(item.failureReason)
    }

    @Test
    fun outgoingItemAllowsOnlyConsistentStatusTransitions() {
        val pending = outgoingItem()
        val sending = pending.transitionTo(
            next = TextTransferStatus.SENDING,
            changedAtEpochMillis = 1_100,
        )
        val delivered = sending.transitionTo(
            next = TextTransferStatus.DELIVERED,
            changedAtEpochMillis = 1_200,
        )

        assertEquals(TextTransferStatus.DELIVERED, delivered.status)
        assertEquals(1_200, delivered.updatedAtEpochMillis)
        assertThrows(IllegalArgumentException::class.java) {
            delivered.transitionTo(
                next = TextTransferStatus.FAILED,
                changedAtEpochMillis = 1_300,
                failureReason = TextTransferFailureReason.CONNECTION_LOST,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            pending.transitionTo(
                next = TextTransferStatus.DELIVERED,
                changedAtEpochMillis = 1_100,
            )
        }
    }

    @Test
    fun failedOutgoingItemCanBeRetriedExplicitly() {
        val failed = outgoingItem()
            .transitionTo(TextTransferStatus.SENDING, changedAtEpochMillis = 1_100)
            .transitionTo(
                next = TextTransferStatus.FAILED,
                changedAtEpochMillis = 1_200,
                failureReason = TextTransferFailureReason.CONNECTION_LOST,
            )

        val retried = failed.transitionTo(
            next = TextTransferStatus.SENDING,
            changedAtEpochMillis = 1_300,
        )

        assertEquals(TextTransferStatus.SENDING, retried.status)
        assertNull(retried.failureReason)
    }

    @Test
    fun incomingBrowserItemIsAcceptedByAndroidOnceCreated() {
        val item = TextTransferItem.incoming(
            id = TextMessageId("message-2"),
            generationId = generationId,
            sessionId = sessionId,
            browserLabel = "Edge on Windows",
            content = "https://example.com",
            contentKind = TextContentKind.LINK,
            receivedAtEpochMillis = 2_000,
        )

        assertEquals(TextTransferDirection.BROWSER_TO_ANDROID, item.direction)
        assertEquals(TextTransferStatus.DELIVERED, item.status)
        assertEquals(TextContentKind.LINK, item.contentKind)
    }

    private fun outgoingItem(): TextTransferItem = TextTransferItem.outgoing(
        id = TextMessageId("message-1"),
        generationId = generationId,
        sessionId = sessionId,
        browserLabel = "Chrome on Windows",
        content = "hello",
        contentKind = TextContentKind.TEXT,
        createdAtEpochMillis = 1_000,
    )
}
