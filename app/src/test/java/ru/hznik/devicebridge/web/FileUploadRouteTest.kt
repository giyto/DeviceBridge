package ru.hznik.devicebridge.web

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.file.FileUploadTarget
import ru.hznik.devicebridge.data.file.FileUploadTargetFactory
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.file.FileTransferPhase

class FileUploadRouteTest {

    @Test
    fun approvedOwnerStreamsRawBodyAndCompletesWithChecksum() {
        val target = RecordingTarget()
        withSessionRouteServer(uploadTargetFactory = FileUploadTargetFactory { _, _ -> target }) { server ->
            val payload = "hello".encodeToByteArray()
            val paired = offerAndApprove(server, payload)

            val response = server.requestBytes(
                "POST",
                "/api/v1/files/upload-1",
                payload,
                server.sameOriginBinaryHeaders(
                    mapOf("Authorization" to "Bearer ${paired.token}"),
                ),
            )

            assertEquals(200, response.statusCode())
            assertArrayEquals(payload, target.output.toByteArray())
            assertEquals(1, target.commits)
            assertEquals(0, target.aborts)
            assertEquals(
                FileTransferPhase.COMPLETED,
                server.fileCoordinator.state.value.item(FileTransferId("upload-1"))?.phase,
            )
        }
    }

    @Test
    fun missingBearerPreApprovalForeignSessionAndLengthMismatchDoNotOpenTarget() {
        var opens = 0
        val factory = FileUploadTargetFactory { _, _ -> opens += 1; RecordingTarget() }
        withSessionRouteServer(uploadTargetFactory = factory) { server ->
            val payload = "hello".encodeToByteArray()
            val owner = server.pairBrowser("Chrome")
            val foreign = server.pairBrowser("Edge")
            createOffer(server, owner.token, payload)

            val missing = server.requestBytes(
                "POST", "/api/v1/files/upload-1", payload, server.sameOriginBinaryHeaders(),
            )
            val beforeApproval = server.requestBytes(
                "POST", "/api/v1/files/upload-1", payload,
                server.sameOriginBinaryHeaders(mapOf("Authorization" to "Bearer ${owner.token}")),
            )
            val foreignResponse = server.requestBytes(
                "POST", "/api/v1/files/upload-1", payload,
                server.sameOriginBinaryHeaders(mapOf("Authorization" to "Bearer ${foreign.token}")),
            )
            kotlinx.coroutines.runBlocking {
                server.fileCoordinator.approve(FileTransferId("upload-1"), FileDestinationId("tree://downloads"))
            }
            val mismatch = server.requestBytes(
                "POST", "/api/v1/files/upload-1", "hey".encodeToByteArray(),
                server.sameOriginBinaryHeaders(mapOf("Authorization" to "Bearer ${owner.token}")),
            )

            assertEquals(401, missing.statusCode())
            assertEquals(403, beforeApproval.statusCode())
            assertEquals(404, foreignResponse.statusCode())
            assertEquals(400, mismatch.statusCode())
            assertEquals(0, opens)
        }
    }

    @Test
    fun acceptedTransferKeepsItsValidationSnapshotWhenSettingChanges() {
        val currentLimit = AtomicLong(5)
        val target = RecordingTarget()
        withSessionRouteServer(
            uploadTargetFactory = FileUploadTargetFactory { _, _ -> target },
            effectiveFileLimitProvider = currentLimit::get,
        ) { server ->
            val payload = "hello".encodeToByteArray()
            val paired = offerAndApprove(server, payload)

            currentLimit.set(1)
            val response = server.requestBytes(
                "POST",
                "/api/v1/files/upload-1",
                payload,
                server.sameOriginBinaryHeaders(
                    mapOf("Authorization" to "Bearer ${paired.token}"),
                ),
            )

            assertEquals(200, response.statusCode())
            assertEquals(1, target.commits)
        }
    }

    @Test
    fun inaccessibleDestinationReturnsDistinctFailureAndReleasesQueueSlot() {
        withSessionRouteServer(uploadTargetFactory = FileUploadTargetFactory { _, _ ->
            error("destination provider unavailable")
        }) { server ->
            val payload = "hello".encodeToByteArray()
            val paired = offerAndApprove(server, payload)

            val response = server.requestBytes(
                "POST", "/api/v1/files/upload-1", payload,
                server.sameOriginBinaryHeaders(mapOf("Authorization" to "Bearer ${paired.token}")),
            )

            assertEquals(503, response.statusCode())
            assertTrue(response.body().contains("DESTINATION_UNAVAILABLE"))
            assertEquals(
                FileTransferFailure.StorageUnavailable,
                server.fileCoordinator.state.value.item(FileTransferId("upload-1"))?.failure,
            )
        }
    }

    @Test
    fun noSpaceDuringWriteReturnsDistinctFailureAndAbortsPartialOutput() {
        val target = NoSpaceTarget()
        withSessionRouteServer(uploadTargetFactory = FileUploadTargetFactory { _, _ -> target }) { server ->
            val payload = "hello".encodeToByteArray()
            val paired = offerAndApprove(server, payload)

            val response = server.requestBytes(
                "POST", "/api/v1/files/upload-1", payload,
                server.sameOriginBinaryHeaders(mapOf("Authorization" to "Bearer ${paired.token}")),
            )

            assertEquals(400, response.statusCode())
            assertTrue(response.body().contains("INSUFFICIENT_SPACE"))
            assertEquals(
                FileTransferFailure.InsufficientSpace,
                server.fileCoordinator.state.value.item(FileTransferId("upload-1"))?.failure,
            )
        }
    }

    private fun offerAndApprove(
        server: SessionRouteTestServer,
        payload: ByteArray,
    ): ru.hznik.devicebridge.core.protocol.session.SessionConfirmResponse {
        val paired = server.pairBrowser("Chrome")
        createOffer(server, paired.token, payload)
        kotlinx.coroutines.runBlocking {
            assertEquals(
                ru.hznik.devicebridge.domain.file.FileTransferOperationResult.Accepted,
                server.fileCoordinator.approve(
                    FileTransferId("upload-1"),
                    FileDestinationId("tree://downloads"),
                ),
            )
        }
        return paired
    }

    private fun createOffer(server: SessionRouteTestServer, token: String, payload: ByteArray) {
        val response = server.request(
            "POST",
            "/api/v1/files",
            """{"protocolVersion":1,"messageId":"offer-1","type":"file.offer","timestamp":123,"batchId":"batch-1","items":[{"transferId":"upload-1","displayName":"safe.bin","sizeBytes":${payload.size},"mimeType":"application/octet-stream","sha256":"${sha256(payload)}","direction":"BROWSER_TO_ANDROID"}]}""",
            server.sameOriginJsonHeaders(mapOf("Authorization" to "Bearer $token")),
        )
        assertEquals(200, response.statusCode())
    }

    private class RecordingTarget : FileUploadTarget {
        val output = ByteArrayOutputStream()
        var commits = 0
        var aborts = 0
        override fun outputStream() = output
        override suspend fun commit() { commits += 1 }
        override suspend fun abort() { aborts += 1 }
        override suspend fun close() = Unit
    }

    private class NoSpaceTarget : FileUploadTarget {
        override fun outputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) {
                throw IOException("ENOSPC: no space left on device")
            }
        }
        override suspend fun commit() = Unit
        override suspend fun abort() = Unit
        override suspend fun close() = Unit
    }


    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
