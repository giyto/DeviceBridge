package ru.hznik.devicebridge.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.core.protocol.session.SessionChallengeResponse
import ru.hznik.devicebridge.core.protocol.session.SessionErrorCode
import ru.hznik.devicebridge.core.protocol.session.SessionErrorEnvelope
import ru.hznik.devicebridge.core.protocol.session.SessionProtocolJson

class SessionChallengeRouteTest {

    @Test
    fun validSameOriginRequestCreatesPublicChallengeWithoutSecret() =
        withSessionRouteServer { server ->
            val response = server.request(
                method = "POST",
                path = "/api/v1/session/challenge",
                body = """{"protocolVersion":1,"clientLabel":"Chrome"}""",
                headers = server.sameOriginJsonHeaders(),
            )

            assertEquals(200, response.statusCode())
            val payload = SessionProtocolJson.decode<SessionChallengeResponse>(response.body())
            assertEquals(1, payload.protocolVersion)
            assertEquals(5, payload.attemptsRemaining)
            assertFalse(response.body().contains("123456"))
            assertFalse(response.body().contains("token", ignoreCase = true))
        }

    @Test
    fun invalidVersionMetadataAndCapacityUseStableErrors() =
        withSessionRouteServer(maxChallenges = 1) { server ->
            val unsupported = server.request(
                "POST",
                "/api/v1/session/challenge",
                """{"protocolVersion":2,"clientLabel":"Chrome"}""",
                server.sameOriginJsonHeaders(),
            )
            val invalid = server.request(
                "POST",
                "/api/v1/session/challenge",
                """{"protocolVersion":1,"clientLabel":"bad\nlabel"}""",
                server.sameOriginJsonHeaders(),
            )
            val first = server.request(
                "POST",
                "/api/v1/session/challenge",
                """{"protocolVersion":1,"clientLabel":"Chrome"}""",
                server.sameOriginJsonHeaders(),
            )
            val full = server.request(
                "POST",
                "/api/v1/session/challenge",
                """{"protocolVersion":1,"clientLabel":"Edge"}""",
                server.sameOriginJsonHeaders(),
            )

            assertEquals(400, unsupported.statusCode())
            assertEquals(
                SessionErrorCode.UNSUPPORTED_VERSION,
                SessionProtocolJson.decode<SessionErrorEnvelope>(unsupported.body()).error.code,
            )
            assertEquals(400, invalid.statusCode())
            assertEquals(200, first.statusCode())
            assertEquals(429, full.statusCode())
            assertEquals(
                SessionErrorCode.CAPACITY_REACHED,
                SessionProtocolJson.decode<SessionErrorEnvelope>(full.body()).error.code,
            )
        }

    @Test
    fun missingOriginWrongContentTypeAndOversizedBodyAreRejectedWithoutCors() =
        withSessionRouteServer { server ->
            val body = """{"protocolVersion":1,"clientLabel":"Chrome"}"""
            val missingOrigin = server.request(
                "POST",
                "/api/v1/session/challenge",
                body,
                mapOf("Content-Type" to "application/json"),
            )
            val wrongType = server.request(
                "POST",
                "/api/v1/session/challenge",
                body,
                mapOf("Origin" to "http://${server.authority}", "Content-Type" to "text/plain"),
            )
            val oversized = server.request(
                "POST",
                "/api/v1/session/challenge",
                "x".repeat(4_097),
                server.sameOriginJsonHeaders(),
            )

            assertEquals(403, missingOrigin.statusCode())
            assertEquals(400, wrongType.statusCode())
            assertEquals(400, oversized.statusCode())
            assertTrue(missingOrigin.headers().firstValue("access-control-allow-origin").isEmpty)
        }

    @Test
    fun blankAndOver64CharacterLabelsAreRejectedBeforeCreatingChallenge() =
        withSessionRouteServer { server ->
            val blank = server.request(
                "POST",
                "/api/v1/session/challenge",
                """{"protocolVersion":1,"clientLabel":"   "}""",
                server.sameOriginJsonHeaders(),
            )
            val oversized = server.request(
                "POST",
                "/api/v1/session/challenge",
                """{"protocolVersion":1,"clientLabel":"${"x".repeat(65)}"}""",
                server.sameOriginJsonHeaders(),
            )

            assertEquals(400, blank.statusCode())
            assertEquals(400, oversized.statusCode())
            assertTrue(server.coordinator.state.value.pendingRequests.isEmpty())
        }
}
