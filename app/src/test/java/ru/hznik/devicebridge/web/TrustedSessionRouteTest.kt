package ru.hznik.devicebridge.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.core.protocol.session.SessionConfirmResponse
import ru.hznik.devicebridge.core.protocol.session.SessionErrorCode
import ru.hznik.devicebridge.core.protocol.session.SessionErrorEnvelope
import ru.hznik.devicebridge.core.protocol.session.SessionProtocolJson

class TrustedSessionRouteTest {
    @Test
    fun sameOriginTrustedCredentialIsExchangedForNewTemporarySessionToken() =
        withSessionRouteServer { server ->
            val paired = server.pairTrustedBrowser("Edge")
            val credential = requireNotNull(paired.trustedCredential)

            val response = server.request(
                "POST",
                "/api/v1/session/trusted",
                body(credential),
                server.sameOriginJsonHeaders(),
            )

            assertEquals(200, response.statusCode())
            val exchanged = SessionProtocolJson.decode<SessionConfirmResponse>(response.body())
            assertTrue(exchanged.token.isNotBlank())
            assertTrue(exchanged.token != credential)
            assertFalse(response.body().contains("trustedCredential"))
            assertTrue(
                runBlocking {
                    server.coordinator.authenticate(server.handle, exchanged.token)
                } != null,
            )
            assertEquals(
                null,
                runBlocking { server.coordinator.authenticate(server.handle, credential) },
            )
        }

    @Test
    fun expiredRevokedMalformedAndCrossOriginCredentialsAreRejected() =
        withSessionRouteServer { server ->
            val expiredPair = server.pairTrustedBrowser("Chrome")
            val expiredCredential = requireNotNull(expiredPair.trustedCredential)
            server.trustedBrowserRepository.forceExpired(expiredCredential)
            val expired = server.request(
                "POST",
                "/api/v1/session/trusted",
                body(expiredCredential),
                server.sameOriginJsonHeaders(),
            )

            val revokedPair = server.pairTrustedBrowser("Firefox")
            val revokedCredential = requireNotNull(revokedPair.trustedCredential)
            val trustedId = server.trustedBrowserRepository.trustedBrowsers.value
                .first { it.browserLabel == "Firefox" }
                .id
            runBlocking { server.coordinator.revokeTrustedBrowser(trustedId) }
            val revoked = server.request(
                "POST",
                "/api/v1/session/trusted",
                body(revokedCredential),
                server.sameOriginJsonHeaders(),
            )
            val malformed = server.request(
                "POST",
                "/api/v1/session/trusted",
                """{"protocolVersion":1,"trustedCredential":"bad value"}""",
                server.sameOriginJsonHeaders(),
            )
            val crossOrigin = server.request(
                "POST",
                "/api/v1/session/trusted",
                body(revokedCredential),
                mapOf(
                    "Origin" to "http://evil.invalid",
                    "Content-Type" to "application/json",
                ),
            )

            assertEquals(410, expired.statusCode())
            assertEquals(SessionErrorCode.EXPIRED, expired.errorCode())
            assertEquals(401, revoked.statusCode())
            assertEquals(SessionErrorCode.UNAUTHORIZED, revoked.errorCode())
            assertEquals(400, malformed.statusCode())
            assertEquals(403, crossOrigin.statusCode())
        }

    @Test
    fun invalidCredentialAttemptsAreRateLimitedAndOversizedPayloadIsRejected() =
        withSessionRouteServer { server ->
            val attempts = (1..5).map { index ->
                server.request(
                    "POST",
                    "/api/v1/session/trusted",
                    body("wrong-credential-$index"),
                    server.sameOriginJsonHeaders(),
                )
            }
            val oversized = server.request(
                "POST",
                "/api/v1/session/trusted",
                "x".repeat(4_097),
                server.sameOriginJsonHeaders(),
            )

            assertEquals(401, attempts.first().statusCode())
            assertEquals(429, attempts.last().statusCode())
            assertEquals(SessionErrorCode.RATE_LIMITED, attempts.last().errorCode())
            assertEquals(400, oversized.statusCode())
        }

    private fun body(credential: String): String =
        """{"protocolVersion":1,"trustedCredential":"$credential"}"""

    private fun java.net.http.HttpResponse<String>.errorCode(): SessionErrorCode =
        SessionProtocolJson.decode<SessionErrorEnvelope>(body()).error.code
}
