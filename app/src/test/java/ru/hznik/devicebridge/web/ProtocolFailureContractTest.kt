package ru.hznik.devicebridge.web

import java.io.ByteArrayOutputStream
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.hznik.devicebridge.data.file.FileUploadTarget
import ru.hznik.devicebridge.data.file.FileUploadTargetFactory
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferId

class ProtocolFailureContractTest {

    @Test
    fun pairingFailuresExposeStableSafeErrorCodes() = withSessionRouteServer(
        confirmWaitTimeoutMs = 50,
    ) { server ->
        val unsupported = server.request(
            "POST",
            "/api/v1/session/challenge",
            """{"protocolVersion":2,"clientLabel":"Chrome"}""",
            server.sameOriginJsonHeaders(),
        )
        val challenge = server.request(
            "POST",
            "/api/v1/session/challenge",
            """{"protocolVersion":1,"clientLabel":"Chrome"}""",
            server.sameOriginJsonHeaders(),
        )
        val challengeId = Json.parseToJsonElement(challenge.body()).jsonObject
            .getValue("challengeId").jsonPrimitive.content
        val invalidCode = server.request(
            "POST",
            "/api/v1/session/confirm",
            confirmBody(challengeId, "000000"),
            server.sameOriginJsonHeaders(),
        )
        server.advanceClockTo(301_000)
        val expired = server.request(
            "POST",
            "/api/v1/session/confirm",
            confirmBody(challengeId, "123456"),
            server.sameOriginJsonHeaders(),
        )

        assertEquals("protocol_version_unsupported", unsupported.sessionErrorCode())
        assertEquals("invalid_pairing_code", invalidCode.sessionErrorCode())
        assertEquals("pairing_request_expired", expired.sessionErrorCode())
        listOf(unsupported, invalidCode, expired).forEach(::assertNoSecrets)
    }

    @Test
    fun deniedUnauthorizedAndRevokedSessionsExposeStableSafeErrorCodes() =
        withSessionRouteServer { server ->
            val challenge = server.request(
                "POST",
                "/api/v1/session/challenge",
                """{"protocolVersion":1,"clientLabel":"Chrome"}""",
                server.sameOriginJsonHeaders(),
            )
            val challengeId = Json.parseToJsonElement(challenge.body()).jsonObject
                .getValue("challengeId").jsonPrimitive.content
            val deniedFuture = server.requestAsync(
                "POST",
                "/api/v1/session/confirm",
                confirmBody(challengeId, "123456"),
                server.sameOriginJsonHeaders(),
            )
            repeat(100) {
                val pending = server.coordinator.state.value.pendingRequests.singleOrNull()
                if (pending != null) {
                    runBlocking { server.coordinator.deny(pending.id) }
                    return@repeat
                }
                Thread.sleep(10)
            }
            val denied = deniedFuture.get(2, TimeUnit.SECONDS)
            val unauthorized = server.request(
                "GET",
                "/api/v1/status",
                headers = mapOf(
                    "Origin" to "http://${server.authority}",
                    "Authorization" to "Bearer bearer-secret",
                ),
            )
            val paired = server.pairBrowser("Edge")
            server.request(
                "DELETE",
                "/api/v1/session",
                headers = mapOf(
                    "Origin" to "http://${server.authority}",
                    "Authorization" to "Bearer ${paired.token}",
                ),
            )
            val revoked = server.request(
                "GET",
                "/api/v1/status",
                headers = mapOf(
                    "Origin" to "http://${server.authority}",
                    "Authorization" to "Bearer ${paired.token}",
                ),
            )

            assertEquals("pairing_denied", denied.sessionErrorCode())
            assertEquals("session_unauthorized", unauthorized.sessionErrorCode())
            assertEquals("session_unauthorized", revoked.sessionErrorCode())
            listOf(denied, unauthorized, revoked).forEach(::assertNoSecrets)
        }

    @Test
    fun fileLimitAndChecksumFailuresExposeStableSafeErrorCodes() {
        val target = RecordingTarget()
        withSessionRouteServer(
            effectiveFileLimitBytes = 5,
            uploadTargetFactory = FileUploadTargetFactory { _, _ -> target },
        ) { server ->
            val paired = server.pairBrowser("Chrome")
            val headers = server.sameOriginJsonHeaders(
                mapOf("Authorization" to "Bearer ${paired.token}"),
            )
            val tooLarge = server.request(
                "POST",
                "/api/v1/files",
                offerBody("too-large", 6, "a".repeat(64)),
                headers,
            )
            val offered = server.request(
                "POST",
                "/api/v1/files",
                offerBody("checksum-file", 5, "a".repeat(64)),
                headers,
            )
            assertEquals(200, offered.statusCode())
            runBlocking {
                server.fileCoordinator.approve(
                    FileTransferId("checksum-file"),
                    FileDestinationId("tree://downloads"),
                )
            }
            val checksum = server.requestBytes(
                "POST",
                "/api/v1/files/checksum-file",
                "hello".encodeToByteArray(),
                server.sameOriginBinaryHeaders(
                    mapOf("Authorization" to "Bearer ${paired.token}"),
                ),
            )

            assertEquals("file_too_large", tooLarge.fileErrorCode())
            assertEquals("file_checksum_mismatch", checksum.fileErrorCode())
            listOf(tooLarge, checksum).forEach(::assertNoSecrets)
        }
    }

    private fun confirmBody(challengeId: String, code: String): String =
        """{"protocolVersion":1,"challengeId":"$challengeId","code":"$code","clientLabel":"Chrome"}"""

    private fun offerBody(transferId: String, sizeBytes: Long, sha256: String): String =
        """{"protocolVersion":1,"messageId":"offer-$transferId","type":"file.offer","timestamp":123,"batchId":"batch-$transferId","items":[{"transferId":"$transferId","displayName":"safe.bin","sizeBytes":$sizeBytes,"mimeType":"application/octet-stream","sha256":"$sha256","direction":"BROWSER_TO_ANDROID"}]}"""

    private fun HttpResponse<String>.sessionErrorCode(): String =
        Json.parseToJsonElement(body()).jsonObject
            .getValue("error").jsonObject
            .getValue("errorCode").jsonPrimitive.content

    private fun HttpResponse<String>.fileErrorCode(): String =
        Json.parseToJsonElement(body()).jsonObject
            .getValue("errorCode").jsonPrimitive.content

    private fun assertNoSecrets(response: HttpResponse<String>) {
        val body = response.body()
        listOf(
            "123456",
            "\"000000\"",
            "bearer-secret",
            "tree://downloads",
            "safe.bin",
            "java.lang",
        ).forEach { secret ->
            assertFalse(body.contains(secret))
        }
    }

    private class RecordingTarget : FileUploadTarget {
        private val output = ByteArrayOutputStream()

        override fun outputStream() = output
        override suspend fun commit() = Unit
        override suspend fun abort() = Unit
        override suspend fun close() = Unit
    }
}
