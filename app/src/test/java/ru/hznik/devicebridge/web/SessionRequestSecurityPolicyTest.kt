package ru.hznik.devicebridge.web

import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRequestSecurityPolicyTest {

    @Test
    fun jsonApiRequiresExactHostSameOriginAndJsonContentType() {
        val allowed = setOf("192.168.1.20:8787")

        assertEquals(
            RequestGuardResult.Allowed,
            SessionRequestSecurityPolicy.validateJsonApi(
                host = "192.168.1.20:8787",
                origin = "http://192.168.1.20:8787",
                contentType = "application/json; charset=UTF-8",
                contentLength = 100,
                allowedHosts = allowed,
            ),
        )
        assertEquals(
            RequestGuardResult.Rejected(HttpStatusCode.Forbidden),
            SessionRequestSecurityPolicy.validateJsonApi(
                host = "192.168.1.20:8787",
                origin = null,
                contentType = "application/json",
                contentLength = 100,
                allowedHosts = allowed,
            ),
        )
        assertEquals(
            RequestGuardResult.Rejected(HttpStatusCode.Forbidden),
            SessionRequestSecurityPolicy.validateJsonApi(
                host = "attacker.example",
                origin = "http://attacker.example",
                contentType = "application/json",
                contentLength = 100,
                allowedHosts = allowed,
            ),
        )
        assertEquals(
            RequestGuardResult.Rejected(HttpStatusCode.BadRequest),
            SessionRequestSecurityPolicy.validateJsonApi(
                host = "192.168.1.20:8787",
                origin = "http://192.168.1.20:8787",
                contentType = "text/plain",
                contentLength = 100,
                allowedHosts = allowed,
            ),
        )
        assertEquals(
            RequestGuardResult.Rejected(HttpStatusCode.BadRequest),
            SessionRequestSecurityPolicy.validateJsonApi(
                host = "192.168.1.20:8787",
                origin = "http://192.168.1.20:8787",
                contentType = "application/json",
                contentLength = 4_097,
                allowedHosts = allowed,
            ),
        )
    }

    @Test
    fun websocketUpgradeRequiresExactSameOriginButNoJsonContentType() {
        val allowed = setOf("127.0.0.1:8787")

        assertEquals(
            RequestGuardResult.Allowed,
            SessionRequestSecurityPolicy.validateWebSocket(
                host = "127.0.0.1:8787",
                origin = "http://127.0.0.1:8787",
                allowedHosts = allowed,
            ),
        )
        assertEquals(
            RequestGuardResult.Rejected(HttpStatusCode.Forbidden),
            SessionRequestSecurityPolicy.validateWebSocket(
                host = "127.0.0.1:8787",
                origin = "https://127.0.0.1:8787",
                allowedHosts = allowed,
            ),
        )
    }

    @Test
    fun originSchemeMustMatchTheConnection() {
        val allowed = setOf("192.168.1.20:8787")
        fun check(origin: String, scheme: String) = SessionRequestSecurityPolicy.validateWebSocket(
            host = "192.168.1.20:8787",
            origin = origin,
            allowedHosts = allowed,
            originScheme = scheme,
        )

        assertEquals(RequestGuardResult.Allowed, check("https://192.168.1.20:8787", "https"))
        assertEquals(RequestGuardResult.Allowed, check("http://192.168.1.20:8787", "http"))
        assertTrue(check("https://192.168.1.20:8787", "http") is RequestGuardResult.Rejected)
        assertTrue(check("http://192.168.1.20:8787", "https") is RequestGuardResult.Rejected)
    }

    @Test
    fun policySourceDoesNotInstallPermissiveCors() {
        val source = Files.readString(
            Path.of("src/main/java/ru/hznik/devicebridge/web/SessionRequestSecurityPolicy.kt"),
        )

        assertFalse(source.contains("CORS"))
        assertFalse(source.contains("anyHost"))
        assertFalse(source.contains("Access-Control-Allow-Origin"))
    }
}
