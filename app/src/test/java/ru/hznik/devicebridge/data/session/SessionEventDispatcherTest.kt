package ru.hznik.devicebridge.data.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.text.TextSessionEventGateway
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferItem

@OptIn(ExperimentalCoroutinesApi::class)
class SessionEventDispatcherTest {

    @Test
    fun preservesControlEventOrderWithinSession() = runTest {
        val received = mutableListOf<String>()
        val dispatcher = dispatcher()
        dispatcher.attach(sessionId) { event ->
            received += event.payload()
            true
        }

        assertTrue(dispatcher.publish(sessionId, control("first")))
        assertTrue(dispatcher.publish(sessionId, control("second")))

        assertEquals(listOf("first", "second"), received)
    }

    @Test
    fun coalescesPendingProgressToLatestValue() = runTest {
        val gate = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        val dispatcher = dispatcher()
        dispatcher.attach(sessionId) { event ->
            if (event.payload() == "blocker") gate.await()
            received += event.payload()
            true
        }
        val blocker = async { dispatcher.publish(sessionId, control("blocker")) }
        runCurrent()

        val oldProgress = async { dispatcher.publish(sessionId, progress("10")) }
        val latestProgress = async { dispatcher.publish(sessionId, progress("20")) }
        runCurrent()

        assertTrue(oldProgress.isCompleted)
        assertTrue(oldProgress.await())
        assertFalse(latestProgress.isCompleted)

        gate.complete(Unit)
        runCurrent()

        assertTrue(blocker.await())
        assertTrue(latestProgress.await())
        assertEquals(listOf("blocker", "20"), received)
    }

    @Test
    fun prioritizesControlAndTerminalEventsOverProgress() = runTest {
        val gate = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        val dispatcher = dispatcher()
        dispatcher.attach(sessionId) { event ->
            if (event.payload() == "blocker") gate.await()
            received += event.payload()
            true
        }
        val blocker = async { dispatcher.publish(sessionId, control("blocker")) }
        runCurrent()

        val progress = async { dispatcher.publish(sessionId, progress("progress")) }
        val control = async { dispatcher.publish(sessionId, control("control")) }
        val terminal = async { dispatcher.publish(sessionId, terminal("terminal")) }
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertTrue(blocker.await())
        assertTrue(progress.await())
        assertTrue(control.await())
        assertTrue(terminal.await())
        assertEquals(listOf("blocker", "control", "terminal", "progress"), received)
    }

    @Test
    fun slowConsumerKeepsOnlyBoundedProgressBacklog() = runTest {
        val gate = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        val dispatcher = dispatcher(maxPendingEvents = 2)
        dispatcher.attach(sessionId) { event ->
            if (event.payload() == "blocker") gate.await()
            received += event.payload()
            true
        }
        val blocker = async { dispatcher.publish(sessionId, control("blocker")) }
        runCurrent()
        val updates = (1..50).map { value ->
            async { dispatcher.publish(sessionId, progress(value.toString())) }
        }
        runCurrent()

        assertTrue(updates.count { it.isCompleted } >= 49)

        gate.complete(Unit)
        runCurrent()
        assertTrue(blocker.await())
        updates.forEach { assertTrue(it.await()) }
        assertEquals(listOf("blocker", "50"), received)
    }

    @Test
    fun remainsCompatibleWithTextDeliveryGateway() = runTest {
        var delivered: TextTransferItem? = null
        val dispatcher = dispatcher()
        dispatcher.attach(sessionId) { event ->
            delivered = (event as SessionOutboundEvent.Text).item
            true
        }
        val gateway: TextSessionEventGateway = dispatcher
        val item = outgoingItem()

        assertTrue(gateway.deliver(item))
        assertEquals(item, delivered)
    }

    private fun TestScope.dispatcher(maxPendingEvents: Int = 8) = SessionEventDispatcher(
        scope = backgroundScope,
        maxPendingEvents = maxPendingEvents,
    )

    private fun control(payload: String) =
        SessionOutboundEvent.Control(messageId = payload, payload = payload)

    private fun progress(payload: String) = SessionOutboundEvent.FileProgress(
        transferId = FileTransferId("transfer-1"),
        payload = payload,
    )

    private fun terminal(payload: String) = SessionOutboundEvent.FileTerminal(
        transferId = FileTransferId("transfer-1"),
        payload = payload,
    )

    private fun SessionOutboundEvent.payload(): String = when (this) {
        is SessionOutboundEvent.Control -> payload
        is SessionOutboundEvent.FileProgress -> payload
        is SessionOutboundEvent.FileTerminal -> payload
        is SessionOutboundEvent.Text -> item.content
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
