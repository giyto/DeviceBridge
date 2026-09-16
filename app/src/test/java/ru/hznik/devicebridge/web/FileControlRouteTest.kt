package ru.hznik.devicebridge.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileCommandId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferEvent
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.session.BrowserSessionId

class FileControlRouteTest {

    @Test
    fun matchingVerificationCompletesAndDuplicateIsRejected() =
        withSessionRouteServer { server ->
            val paired = server.pairBrowser("Chrome")
            prepareVerifying(server, paired.sessionId, "verify-1", "a".repeat(64))
            val headers = server.sameOriginJsonHeaders(
                mapOf("Authorization" to "Bearer ${paired.token}"),
            )

            val accepted = server.request(
                "POST", "/api/v1/files/verify-1/verify", verifyBody("verify-1", "a".repeat(64)), headers,
            )
            val duplicate = server.request(
                "POST", "/api/v1/files/verify-1/verify", verifyBody("verify-1", "a".repeat(64)), headers,
            )

            assertEquals(200, accepted.statusCode())
            assertEquals(409, duplicate.statusCode())
            assertEquals(
                FileTransferPhase.COMPLETED,
                server.fileCoordinator.state.value.item(FileTransferId("verify-1"))?.phase,
            )
        }

    @Test
    fun checksumMismatchFailsAndForeignSessionCannotVerify() =
        withSessionRouteServer { server ->
            val owner = server.pairBrowser("Chrome")
            val foreign = server.pairBrowser("Edge")
            prepareVerifying(server, owner.sessionId, "verify-1", "a".repeat(64))

            val foreignResponse = server.request(
                "POST", "/api/v1/files/verify-1/verify", verifyBody("verify-1", "a".repeat(64)),
                server.sameOriginJsonHeaders(mapOf("Authorization" to "Bearer ${foreign.token}")),
            )
            val mismatch = server.request(
                "POST", "/api/v1/files/verify-1/verify", verifyBody("verify-1", "b".repeat(64)),
                server.sameOriginJsonHeaders(mapOf("Authorization" to "Bearer ${owner.token}")),
            )

            assertEquals(404, foreignResponse.statusCode())
            assertEquals(422, mismatch.statusCode())
            assertEquals(
                FileTransferPhase.FAILED,
                server.fileCoordinator.state.value.item(FileTransferId("verify-1"))?.phase,
            )
        }

    @Test
    fun ownerCanCancelQueuedAndActiveButForeignSessionCannot() =
        withSessionRouteServer { server ->
            val owner = server.pairBrowser("Chrome")
            val foreign = server.pairBrowser("Edge")
            createOffers(server, owner.sessionId, "active", "queued")
            val ownerHeaders = server.sameOriginJsonHeaders(
                mapOf("Authorization" to "Bearer ${owner.token}"),
            )

            val foreignResponse = server.request(
                "DELETE", "/api/v1/transfers/active", headers = server.sameOriginJsonHeaders(
                    mapOf("Authorization" to "Bearer ${foreign.token}"),
                ),
            )
            val queued = server.request("DELETE", "/api/v1/transfers/queued", headers = ownerHeaders)
            val active = server.request("DELETE", "/api/v1/transfers/active", headers = ownerHeaders)

            assertEquals(404, foreignResponse.statusCode())
            assertEquals(200, queued.statusCode())
            assertEquals(200, active.statusCode())
            assertEquals(FileTransferPhase.CANCELLED, server.fileCoordinator.state.value.item(FileTransferId("queued"))?.phase)
            assertEquals(FileTransferPhase.CANCELLED, server.fileCoordinator.state.value.item(FileTransferId("active"))?.phase)
        }

    private fun prepareVerifying(
        server: SessionRouteTestServer,
        sessionId: String,
        id: String,
        hash: String,
    ) = runBlocking {
        createOffers(server, sessionId, id, sha256 = hash)
        server.fileCoordinator.transition(FileTransferId(id), FileTransferEvent.Started)
        server.fileCoordinator.transition(FileTransferId(id), FileTransferEvent.Progressed(1, 1))
        server.fileCoordinator.transition(FileTransferId(id), FileTransferEvent.Verifying)
    }

    private fun createOffers(
        server: SessionRouteTestServer,
        sessionId: String,
        vararg ids: String,
        sha256: String = "a".repeat(64),
    ) = runBlocking {
        server.fileCoordinator.create(
            CreateFileTransfersRequest(
                commandId = FileCommandId("command-${ids.first()}"),
                generationId = server.handle.generationId,
                ownerSessionId = BrowserSessionId(sessionId),
                files = ids.map { id ->
                    FileTransferMetadata(
                        id = FileTransferId(id),
                        displayName = "$id.bin",
                        sizeBytes = 1,
                        mimeType = "application/octet-stream",
                        sha256 = sha256,
                        direction = FileTransferDirection.ANDROID_TO_BROWSER,
                    )
                },
            ),
        )
    }

    private fun verifyBody(transferId: String, hash: String): String =
        """{"protocolVersion":1,"messageId":"verify-message","type":"file.verify","timestamp":123,"transferId":"$transferId","sizeBytes":1,"sha256":"$hash"}"""
}
