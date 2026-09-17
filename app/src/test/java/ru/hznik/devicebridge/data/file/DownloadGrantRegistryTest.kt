package ru.hznik.devicebridge.data.file

import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId

class DownloadGrantRegistryTest {
    private var now = 1_000L
    private val tokenSource = CountingGrantTokenSource()
    private val registry = DownloadGrantRegistry(
        nowEpochMillis = { now },
        tokenSource = tokenSource,
    )

    @Test
    fun issuesRandom128BitGrantWithThirtySecondScopedPath() = runTest {
        val first = registry.issue(generation, session, transfer)
        val second = registry.issue(generation, session, transfer)

        assertNotEquals(first.token, second.token)
        assertEquals(16, Base64.getUrlDecoder().decode(first.token).size)
        assertEquals(31_000L, first.expiresAtEpochMillis)
        assertTrue(first.downloadPath.startsWith("/api/v1/files/" + transfer.value + "?grant="))
        assertTrue(first.downloadPath.endsWith(first.token))
        assertFalse(first.downloadPath.contains(session.value))
        assertFalse(first.downloadPath.contains("bearer", ignoreCase = true))
    }

    @Test
    fun consumesGrantOnlyOnceAndOnlyForExactScope() = runTest {
        val grant = registry.issue(generation, session, transfer)

        assertNull(
            registry.consume(
                grant.token,
                generation,
                BrowserSessionId("other-session"),
                transfer,
            ),
        )
        assertEquals(
            DownloadGrantScope(generation, session, transfer),
            registry.consume(grant.token, generation, session, transfer),
        )
        assertNull(registry.consume(grant.token, generation, session, transfer))
    }

    @Test
    fun rejectsExpiredGrantWithoutRevealingScope() = runTest {
        val grant = registry.issue(generation, session, transfer)
        now = grant.expiresAtEpochMillis

        assertNull(registry.consume(grant.token, generation, session, transfer))
    }

    @Test
    fun revokeAndGenerationStopInvalidateOutstandingGrants() = runTest {
        val revoked = registry.issue(generation, session, transfer)
        val stopped = registry.issue(
            generation,
            BrowserSessionId("session-2"),
            FileTransferId("transfer-2"),
        )

        registry.invalidateSession(generation, session)
        assertNull(registry.consume(revoked.token, generation, session, transfer))

        registry.invalidateGeneration(generation)
        assertNull(
            registry.consume(
                stopped.token,
                generation,
                BrowserSessionId("session-2"),
                FileTransferId("transfer-2"),
            ),
        )
    }

    @Test
    fun transferCancellationInvalidatesOnlyThatTransfersOutstandingGrants() = runTest {
        val cancelled = registry.issue(generation, session, transfer)
        val retainedTransfer = FileTransferId("transfer-2")
        val retained = registry.issue(generation, session, retainedTransfer)

        registry.invalidateTransfer(generation, transfer)

        assertNull(registry.consume(cancelled.token, generation, session, transfer))
        assertEquals(
            DownloadGrantScope(generation, session, retainedTransfer),
            registry.consume(retained.token, generation, session, retainedTransfer),
        )
    }

    private class CountingGrantTokenSource : DownloadGrantTokenSource {
        private var value = 0

        override fun nextBytes(destination: ByteArray) {
            value += 1
            destination.fill(value.toByte())
        }
    }

    private companion object {
        val generation = ServerGenerationId(7)
        val session = BrowserSessionId("session-1")
        val transfer = FileTransferId("transfer-1")
    }
}
