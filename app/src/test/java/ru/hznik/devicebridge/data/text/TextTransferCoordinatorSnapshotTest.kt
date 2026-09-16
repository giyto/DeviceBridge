package ru.hznik.devicebridge.data.text

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.text.IncomingTextRequest
import ru.hznik.devicebridge.domain.text.TextMessageId

class TextTransferCoordinatorSnapshotTest {

    private val generationId = ServerGenerationId(7)
    private val chrome = BrowserSessionId("session-chrome")
    private val edge = BrowserSessionId("session-edge")

    @Test
    fun snapshotContainsOnlyItemsVisibleToRequestedSession() = runTest {
        val coordinator = TextTransferCoordinator(nowEpochMillis = { 5_000 })
        coordinator.activate(generationId)
        coordinator.acceptIncoming(request("chrome-1", chrome, "one"))
        coordinator.acceptIncoming(request("edge-1", edge, "two"))

        val chromeSnapshot = coordinator.snapshotFor(generationId, chrome)
        val edgeSnapshot = coordinator.snapshotFor(generationId, edge)

        assertEquals(listOf("chrome-1"), chromeSnapshot.map { it.id.value })
        assertEquals(listOf("edge-1"), edgeSnapshot.map { it.id.value })
    }

    @Test
    fun repeatedPayloadAndRepeatedSnapshotNeverDuplicateMessageId() = runTest {
        val coordinator = TextTransferCoordinator(nowEpochMillis = { 5_000 })
        coordinator.activate(generationId)
        val request = request("chrome-1", chrome, "one")
        coordinator.acceptIncoming(request)
        coordinator.acceptIncoming(request.copy(requestedAtEpochMillis = 9_000))

        val first = coordinator.snapshotFor(generationId, chrome)
        val second = coordinator.snapshotFor(generationId, chrome)

        assertEquals(first, second)
        assertEquals(1, second.map { it.id }.distinct().size)
    }

    @Test
    fun staleOrClosedGenerationHasNoSnapshot() = runTest {
        val coordinator = TextTransferCoordinator(nowEpochMillis = { 5_000 })
        coordinator.activate(generationId)
        coordinator.acceptIncoming(request("chrome-1", chrome, "one"))

        assertTrue(coordinator.snapshotFor(ServerGenerationId(8), chrome).isEmpty())
        coordinator.close(generationId)
        assertTrue(coordinator.snapshotFor(generationId, chrome).isEmpty())
    }

    private fun request(
        id: String,
        sessionId: BrowserSessionId,
        content: String,
    ) = IncomingTextRequest(
        id = TextMessageId(id),
        generationId = generationId,
        sessionId = sessionId,
        browserLabel = "Browser",
        content = content,
        requestedAtEpochMillis = 1_000,
    )
}
