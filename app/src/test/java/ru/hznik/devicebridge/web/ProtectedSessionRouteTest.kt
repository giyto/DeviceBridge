package ru.hznik.devicebridge.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.core.protocol.session.SessionErrorCode
import ru.hznik.devicebridge.core.protocol.session.SessionErrorEnvelope
import ru.hznik.devicebridge.core.protocol.session.SessionProtocolJson
import ru.hznik.devicebridge.core.protocol.session.SessionStatusResponse

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
}
