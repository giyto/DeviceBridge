package ru.hznik.devicebridge.data.text

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingCodeState
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.text.SendTextRequest
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferItem
import ru.hznik.devicebridge.domain.text.TextTransferResult
import ru.hznik.devicebridge.domain.text.TextTransferStatus

@OptIn(ExperimentalCoroutinesApi::class)
class TextTransferCoordinatorDeliveryTest {

    private val generationId = ServerGenerationId(7)
    private val firstSession = session("session-1", "Chrome")
    private val secondSession = session("session-2", "Edge")

    @Test
    fun sendTargetsOnlySelectedSessionAndCompletesAfterAcknowledgement() = runTest {
        val gateway = RecordingGateway()
        val coordinator = coordinator(gateway)
        coordinator.activate(generationId)

        val result = async {
            coordinator.send(SendTextRequest(firstSession.id, "hello"))
        }
        runCurrent()

        assertEquals(listOf(firstSession.id), gateway.deliveries.map { it.sessionId })
        assertFalse(result.isCompleted)
        val sent = gateway.deliveries.single()
        assertTrue(coordinator.acknowledge(generationId, firstSession.id, sent.id))

        val delivered = (result.await() as TextTransferResult.Accepted).item
        assertEquals(TextTransferStatus.DELIVERED, delivered.status)
        assertEquals(TextTransferStatus.DELIVERED, coordinator.state.value.items.single().status)
    }

    @Test
    fun connectionLossFailsOnlyPendingItemsForAddressedSession() = runTest {
        val gateway = RecordingGateway()
        val coordinator = coordinator(gateway)
        coordinator.activate(generationId)
        val first = async { coordinator.send(SendTextRequest(firstSession.id, "first")) }
        val second = async { coordinator.send(SendTextRequest(secondSession.id, "second")) }
        runCurrent()

        coordinator.onConnectionLost(firstSession.id)
        runCurrent()

        assertEquals(
            TextTransferStatus.FAILED,
            (first.await() as TextTransferResult.Accepted).item.status,
        )
        assertFalse(second.isCompleted)
        val secondItem = gateway.deliveries.single { it.sessionId == secondSession.id }
        coordinator.acknowledge(generationId, secondSession.id, secondItem.id)
        assertEquals(
            TextTransferStatus.DELIVERED,
            (second.await() as TextTransferResult.Accepted).item.status,
        )
    }

    @Test
    fun missingAcknowledgementFailsAfterBoundedTimeout() = runTest {
        val gateway = RecordingGateway()
        val coordinator = coordinator(gateway, acknowledgementTimeoutMs = 1_000)
        coordinator.activate(generationId)
        val result = async { coordinator.send(SendTextRequest(firstSession.id, "hello")) }
        runCurrent()

        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(
            TextTransferStatus.FAILED,
            (result.await() as TextTransferResult.Accepted).item.status,
        )
    }

    @Test
    fun manualRetryUsesSameMessageIdAndDoesNotCreateDuplicate() = runTest {
        val gateway = RecordingGateway(available = false)
        val coordinator = coordinator(gateway)
        coordinator.activate(generationId)
        val failed = (coordinator.send(SendTextRequest(firstSession.id, "hello"))
            as TextTransferResult.Accepted).item
        assertEquals(TextTransferStatus.FAILED, failed.status)

        gateway.available = true
        val retried = async { coordinator.retry(failed.id) }
        runCurrent()
        val retryDelivery = gateway.deliveries.last()
        assertEquals(failed.id, retryDelivery.id)
        coordinator.acknowledge(generationId, firstSession.id, failed.id)

        val delivered = (retried.await() as TextTransferResult.Accepted).item
        assertEquals(TextTransferStatus.DELIVERED, delivered.status)
        assertEquals(1, coordinator.state.value.items.size)
    }

    @Test
    fun historyFailureDoesNotChangeDeliveredTransferResult() = runTest {
        val gateway = RecordingGateway()
        val coordinator = coordinator(
            gateway = gateway,
            historyRecorder = TextTerminalHistoryRecorder {
                error("history unavailable")
            },
        )
        coordinator.activate(generationId)
        val result = async { coordinator.send(SendTextRequest(firstSession.id, "hello")) }
        runCurrent()
        coordinator.acknowledge(
            generationId,
            firstSession.id,
            gateway.deliveries.single().id,
        )

        assertEquals(
            TextTransferStatus.DELIVERED,
            (result.await() as TextTransferResult.Accepted).item.status,
        )
    }

    private fun coordinator(
        gateway: RecordingGateway,
        acknowledgementTimeoutMs: Long = 10_000,
        historyRecorder: TextTerminalHistoryRecorder = TextTerminalHistoryRecorder { },
    ) = TextTransferCoordinator(
        nowEpochMillis = { 5_000 },
        browserSessionState = { browserState() },
        eventGateway = gateway,
        newMessageId = { TextMessageId("outgoing-1") },
        acknowledgementTimeoutMs = acknowledgementTimeoutMs,
        historyRecorder = historyRecorder,
    )

    private fun browserState() = BrowserSessionState.active(
        generationId = generationId,
        pairingCode = PairingCodeState("123456", 60_000),
        sessions = listOf(firstSession, secondSession),
    )

    private fun session(id: String, label: String) = BrowserSession(
        id = BrowserSessionId(id),
        generationId = generationId,
        browserLabel = label,
        sourceIpv4 = "192.168.1.${if (id.endsWith('1')) 2 else 3}",
        connectedAtElapsedRealtimeMs = 1_000,
    )

    private class RecordingGateway(
        var available: Boolean = true,
    ) : TextSessionEventGateway {
        val deliveries = mutableListOf<TextTransferItem>()

        override suspend fun deliver(item: TextTransferItem): Boolean {
            deliveries += item
            return available
        }
    }
}
