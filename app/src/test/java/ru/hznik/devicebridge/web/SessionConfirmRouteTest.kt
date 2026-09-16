package ru.hznik.devicebridge.web

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.core.protocol.session.SessionChallengeResponse
import ru.hznik.devicebridge.core.protocol.session.SessionConfirmResponse
import ru.hznik.devicebridge.core.protocol.session.SessionErrorCode
import ru.hznik.devicebridge.core.protocol.session.SessionErrorEnvelope
import ru.hznik.devicebridge.core.protocol.session.SessionProtocolJson

class SessionConfirmRouteTest {

    @Test
    fun confirmLongPollReturnsTokenOnlyAfterPhoneApproval() = withSessionRouteServer { server ->
        val challenge = server.challenge("Chrome")
        val future = server.requestAsync(
            "POST",
            "/api/v1/session/confirm",
            server.confirmBody(challenge.challengeId, "123456", "Chrome"),
            server.sameOriginJsonHeaders(),
        )
        server.awaitPendingRequest()
        assertFalse(future.isDone)

        runBlocking { server.coordinator.approve(server.coordinator.state.value.pendingRequests.single().id) }

        val response = future.get(2, TimeUnit.SECONDS)
        assertEquals(200, response.statusCode())
        val payload = SessionProtocolJson.decode<SessionConfirmResponse>(response.body())
        assertTrue(payload.token.isNotBlank())
        assertTrue(payload.sessionId.isNotBlank())
    }

    @Test
    fun denyAndTimeoutNeverReturnToken() {
        withSessionRouteServer { server ->
            val challenge = server.challenge("Edge")
            val future = server.requestAsync(
                "POST",
                "/api/v1/session/confirm",
                server.confirmBody(challenge.challengeId, "123456", "Edge"),
                server.sameOriginJsonHeaders(),
            )
            server.awaitPendingRequest()
            runBlocking { server.coordinator.deny(server.coordinator.state.value.pendingRequests.single().id) }
            val response = future.get(2, TimeUnit.SECONDS)

            assertEquals(403, response.statusCode())
            assertEquals(SessionErrorCode.DENIED, response.errorCode())
            assertFalse(response.body().contains("token", ignoreCase = true))
        }
        withSessionRouteServer(confirmWaitTimeoutMs = 50) { server ->
            val challenge = server.challenge("Firefox")
            val response = server.request(
                "POST",
                "/api/v1/session/confirm",
                server.confirmBody(challenge.challengeId, "123456", "Firefox"),
                server.sameOriginJsonHeaders(),
            )

            assertEquals(408, response.statusCode())
            assertEquals(SessionErrorCode.EXPIRED, response.errorCode())
            assertFalse(response.body().contains("token", ignoreCase = true))
        }
    }

    @Test
    fun wrongExpiredAndBlockedAttemptsUseStableStatusesAndNoToken() = withSessionRouteServer { server ->
        val challenge = server.challenge("Chrome")
        val wrongResponses = (1..5).map {
            server.request(
                "POST",
                "/api/v1/session/confirm",
                server.confirmBody(challenge.challengeId, "000000", "Chrome"),
                server.sameOriginJsonHeaders(),
            )
        }

        assertEquals(401, wrongResponses.first().statusCode())
        assertEquals(SessionErrorCode.INVALID_CODE, wrongResponses.first().errorCode())
        assertEquals(429, wrongResponses.last().statusCode())
        assertEquals(SessionErrorCode.RATE_LIMITED, wrongResponses.last().errorCode())
        assertTrue(wrongResponses.all { !it.body().contains("token", ignoreCase = true) })
    }

    @Test
    fun expiredCodeReturnsGoneWithoutPendingRequest() = withSessionRouteServer { server ->
        val challenge = server.challenge("Chrome")
        server.advanceClockTo(301_000)

        val response = server.request(
            "POST",
            "/api/v1/session/confirm",
            server.confirmBody(challenge.challengeId, "123456", "Chrome"),
            server.sameOriginJsonHeaders(),
        )

        assertEquals(410, response.statusCode())
        assertEquals(SessionErrorCode.EXPIRED, response.errorCode())
        assertTrue(server.coordinator.state.value.pendingRequests.isEmpty())
    }

    @Test
    fun normalizedYandexLabelRemainsLiteralPlainTextInPendingRequest() =
        withSessionRouteServer { server ->
            val rawLabel = "  <b>Яндекс Браузер</b>  "
            val challenge = server.challenge(rawLabel)
            val future = server.requestAsync(
                "POST",
                "/api/v1/session/confirm",
                server.confirmBody(challenge.challengeId, "123456", rawLabel),
                server.sameOriginJsonHeaders(),
            )

            server.awaitPendingRequest()

            assertEquals(
                "<b>Яндекс Браузер</b>",
                server.coordinator.state.value.pendingRequests.single().browserLabel,
            )
            runBlocking {
                server.coordinator.deny(
                    server.coordinator.state.value.pendingRequests.single().id,
                )
            }
            assertEquals(403, future.get(2, TimeUnit.SECONDS).statusCode())
        }

    private fun SessionRouteTestServer.challenge(label: String): SessionChallengeResponse {
        val response = request(
            "POST",
            "/api/v1/session/challenge",
            """{"protocolVersion":1,"clientLabel":"$label"}""",
            sameOriginJsonHeaders(),
        )
        assertEquals(200, response.statusCode())
        return SessionProtocolJson.decode(response.body())
    }

    private fun SessionRouteTestServer.confirmBody(
        challengeId: String,
        code: String,
        label: String,
    ): String =
        """{"protocolVersion":1,"challengeId":"$challengeId","code":"$code","clientLabel":"$label"}"""

    private fun SessionRouteTestServer.awaitPendingRequest() {
        repeat(100) {
            if (coordinator.state.value.pendingRequests.isNotEmpty()) return
            Thread.sleep(10)
        }
        error("Pending request was not published")
    }

    private fun java.net.http.HttpResponse<String>.errorCode(): SessionErrorCode =
        SessionProtocolJson.decode<SessionErrorEnvelope>(body()).error.code
}
