package ru.hznik.devicebridge.web

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileTransferPhase

class FileRouteTest {

    @Test
    fun authorizedBatchCreatesQueuedMetadataWithoutOpeningOutput() =
        withSessionRouteServer { server ->
            val paired = server.pairBrowser("Chrome")
            val response = server.request(
                "POST",
                "/api/v1/files",
                offerBody(),
                server.sameOriginJsonHeaders(
                    mapOf("Authorization" to "Bearer ${paired.token}"),
                ),
            )

            assertEquals(200, response.statusCode())
            val item = server.fileCoordinator.state.value.items.single()
            assertEquals("upload-1", item.metadata.id.value)
            assertEquals(FileTransferPhase.CONNECTING, item.phase)
            assertEquals(0, item.bytesTransferred)
        }

    @Test
    fun missingOrForeignBearerCannotCreateOffer() =
        withSessionRouteServer { server ->
            val missing = server.request(
                "POST",
                "/api/v1/files",
                offerBody(),
                server.sameOriginJsonHeaders(),
            )
            val foreign = server.request(
                "POST",
                "/api/v1/files",
                offerBody(messageId = "offer-2", transferId = "upload-2"),
                server.sameOriginJsonHeaders(
                    mapOf("Authorization" to "Bearer unknown-token"),
                ),
            )

            assertEquals(401, missing.statusCode())
            assertEquals(401, foreign.statusCode())
            assertEquals(0, server.fileCoordinator.state.value.items.size)
        }

    @Test
    fun malformedSchemaWrongDirectionAndOversizeAreRejectedBeforeMutation() =
        withSessionRouteServer { server ->
            val paired = server.pairBrowser("Edge")
            val headers = server.sameOriginJsonHeaders(
                mapOf("Authorization" to "Bearer ${paired.token}"),
            )

            val malformed = server.request("POST", "/api/v1/files", "{}", headers)
            val wrongDirection = server.request(
                "POST",
                "/api/v1/files",
                offerBody(direction = "ANDROID_TO_BROWSER"),
                headers,
            )
            val oversized = server.request(
                "POST",
                "/api/v1/files",
                offerBody(sizeBytes = 1_073_741_825L),
                headers,
            )

            assertEquals(400, malformed.statusCode())
            assertEquals(400, wrongDirection.statusCode())
            assertEquals(413, oversized.statusCode())
            assertEquals(0, server.fileCoordinator.state.value.items.size)
        }

    @Test
    fun repeatedOfferIsIdempotentAndConflictingReplayDoesNotMutateOriginal() =
        withSessionRouteServer { server ->
            val paired = server.pairBrowser("Chrome")
            val headers = server.sameOriginJsonHeaders(
                mapOf("Authorization" to "Bearer ${paired.token}"),
            )
            val original = offerBody()

            val first = server.request("POST", "/api/v1/files", original, headers)
            val repeated = server.request("POST", "/api/v1/files", original, headers)
            val conflict = server.request(
                "POST",
                "/api/v1/files",
                offerBody(displayName = "different.bin"),
                headers,
            )

            assertEquals(200, first.statusCode())
            assertEquals(first.body(), repeated.body())
            assertEquals(409, conflict.statusCode())
            assertEquals(listOf("safe.bin"), server.fileCoordinator.state.value.items.map {
                it.metadata.displayName
            })
        }

    @Test
    fun ownershipComesFromAuthenticatedSessionNotPayload() =
        withSessionRouteServer { server ->
            val first = server.pairBrowser("Chrome")
            val second = server.pairBrowser("Edge")
            val response = server.request(
                "POST",
                "/api/v1/files",
                offerBody(),
                server.sameOriginJsonHeaders(
                    mapOf("Authorization" to "Bearer ${second.token}"),
                ),
            )

            assertEquals(200, response.statusCode())
            assertEquals(second.sessionId, server.fileCoordinator.state.value.items.single().ownerSessionId.value)
            org.junit.Assert.assertNotEquals(
                first.sessionId,
                server.fileCoordinator.state.value.items.single().ownerSessionId.value,
            )
        }

    private fun offerBody(
        messageId: String = "offer-1",
        transferId: String = "upload-1",
        displayName: String = "safe.bin",
        sizeBytes: Long = 5,
        direction: String = "BROWSER_TO_ANDROID",
    ): String = """{
        "protocolVersion":1,
        "messageId":"$messageId",
        "type":"file.offer",
        "timestamp":123,
        "batchId":"batch-1",
        "items":[{
          "transferId":"$transferId",
          "displayName":"$displayName",
          "sizeBytes":$sizeBytes,
          "mimeType":"application/octet-stream",
          "sha256":"${"a".repeat(64)}",
          "direction":"$direction"
        }]
      }""".trimIndent()
}
