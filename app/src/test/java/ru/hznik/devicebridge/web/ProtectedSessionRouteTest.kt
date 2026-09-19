package ru.hznik.devicebridge.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.core.protocol.session.SessionErrorCode
import ru.hznik.devicebridge.core.protocol.session.SessionErrorEnvelope
import ru.hznik.devicebridge.core.protocol.session.SessionProtocolJson
import ru.hznik.devicebridge.core.protocol.session.SessionStatusResponse
import kotlinx.coroutines.runBlocking
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileCommandId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.session.BrowserSessionId

class ProtectedSessionRouteTest {

    @Test
    fun statusRejectsMissingMalformedAndUnknownBearer() = withSessionRouteServer { server ->
        val baseHeaders = mapOf("Origin" to "http://${server.authority}")
        val missing = server.request("GET", "/api/v1/status", headers = baseHeaders)
        val malformed = server.request(
            "GET",
            "/api/v1/status",
            headers = baseHeaders + ("Authorization" to "Basic unknown"),
        )
        val unknown = server.request(
            "GET",
            "/api/v1/status",
            headers = baseHeaders + ("Authorization" to "Bearer unknown"),
        )

        listOf(missing, malformed, unknown).forEach { response ->
            assertEquals(401, response.statusCode())
            assertEquals(
                SessionErrorCode.UNAUTHORIZED,
                SessionProtocolJson.decode<SessionErrorEnvelope>(response.body()).error.code,
            )
        }
    }

    @Test
    fun authorizedStatusContainsOnlyCurrentSessionAndMinimalServerData() =
        withSessionRouteServer { server ->
            val paired = server.pairBrowser("Chrome")
            val response = server.request(
                "GET",
                "/api/v1/status",
                headers = mapOf(
                    "Origin" to "http://${server.authority}",
                    "Authorization" to "Bearer ${paired.token}",
                ),
            )

            assertEquals(200, response.statusCode())
            val status = SessionProtocolJson.decode<SessionStatusResponse>(response.body())
            assertEquals(paired.sessionId, status.sessionId)
            assertEquals(1, status.activeSessionCount)
            assertTrue(status.connected)
            assertFalse(response.body().contains("pairing", ignoreCase = true))
            assertFalse(response.body().contains("token", ignoreCase = true))
            assertFalse(response.body().contains("ktor", ignoreCase = true))
        }

    @Test
    fun authorizedStatusPublishesOnlyEffectiveFileLimitCapability() =
        withSessionRouteServer(effectiveFileLimitBytes = 512) { server ->
            val paired = server.pairBrowser("Chrome")
            val response = server.request(
                "GET",
                "/api/v1/status",
                headers = mapOf(
                    "Origin" to "http://${server.authority}",
                    "Authorization" to "Bearer ${paired.token}",
                ),
            )

            val status = SessionProtocolJson.decode<SessionStatusResponse>(response.body())
            assertEquals(512, status.effectiveFileLimitBytes)
            assertFalse(response.body().contains("retention", ignoreCase = true))
            assertFalse(response.body().contains("destination", ignoreCase = true))
            assertFalse(response.body().contains("deviceName", ignoreCase = true))
        }

    @Test
    fun authorizedStatusRestoresBrowserTabWhenSameOriginGetOmitsOriginHeader() =
        withSessionRouteServer { server ->
            val paired = server.pairBrowser("Chrome")

            val response = server.request(
                "GET",
                "/api/v1/status",
                headers = mapOf("Authorization" to "Bearer ${paired.token}"),
            )

            assertEquals(200, response.statusCode())
            val status = SessionProtocolJson.decode<SessionStatusResponse>(response.body())
            assertEquals(paired.sessionId, status.sessionId)
            assertTrue(status.connected)
        }

    @Test
    fun originlessDeleteRemainsForbiddenAfterStatusRefreshCompatibilityFix() =
        withSessionRouteServer { server ->
            val paired = server.pairBrowser("Chrome")

            val response = server.request(
                "DELETE",
                "/api/v1/session",
                headers = mapOf("Authorization" to "Bearer ${paired.token}"),
            )

            assertEquals(403, response.statusCode())
            assertEquals(1, server.coordinator.state.value.sessions.size)
        }

    @Test
    fun deleteRevokesOnlyCallingSessionAndMakesItsTokenImmediatelyInvalid() =
        withSessionRouteServer { server ->
            val chrome = server.pairBrowser("Chrome")
            val edge = server.pairBrowser("Edge")
            val origin = mapOf("Origin" to "http://${server.authority}")

            val deleted = server.request(
                "DELETE",
                "/api/v1/session",
                headers = origin + ("Authorization" to "Bearer ${chrome.token}"),
            )
            val chromeAfter = server.request(
                "GET",
                "/api/v1/status",
                headers = origin + ("Authorization" to "Bearer ${chrome.token}"),
            )
            val edgeAfter = server.request(
                "GET",
                "/api/v1/status",
                headers = origin + ("Authorization" to "Bearer ${edge.token}"),
            )

            assertEquals(204, deleted.statusCode())
            assertEquals(401, chromeAfter.statusCode())
            assertEquals(200, edgeAfter.statusCode())
            assertEquals(1, server.coordinator.state.value.sessions.size)
        }

    @Test
    fun deleteCancelsFileTransfersOwnedByCallingSessionBeforeRevocation() =
        withSessionRouteServer(enableFileEvents = true) { server ->
            val paired = server.pairBrowser("Chrome")
            runBlocking {
                server.fileCoordinator.create(
                    CreateFileTransfersRequest(
                        commandId = FileCommandId("owned-command"),
                        generationId = server.handle.generationId,
                        ownerSessionId = BrowserSessionId(paired.sessionId),
                        files = listOf(fileMetadata("owned-file")),
                    ),
                )
            }

            val response = server.request(
                "DELETE",
                "/api/v1/session",
                headers = mapOf(
                    "Origin" to "http://${server.authority}",
                    "Authorization" to "Bearer ${paired.token}",
                ),
            )

            assertEquals(204, response.statusCode())
            assertEquals(
                FileTransferPhase.FAILED,
                server.fileCoordinator.state.value.item(FileTransferId("owned-file"))?.phase,
            )
        }

    private fun fileMetadata(id: String) = FileTransferMetadata(
        id = FileTransferId(id),
        displayName = "$id.bin",
        sizeBytes = 1,
        mimeType = "application/octet-stream",
        sha256 = "a".repeat(64),
        direction = FileTransferDirection.BROWSER_TO_ANDROID,
    )
}
