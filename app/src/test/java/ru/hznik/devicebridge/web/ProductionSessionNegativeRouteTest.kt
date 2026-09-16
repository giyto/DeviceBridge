package ru.hznik.devicebridge.web

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionSessionNegativeRouteTest {

    @Test
    fun onlyTextRouteOpensWhileDiagnosticsTrustedBrowserAndFilesStayNotFound() =
        withSessionRouteServer { server ->
            val paired = server.pairBrowser("Chrome")
            val headers = mapOf(
                "Origin" to "http://${server.authority}",
                "Authorization" to "Bearer ${paired.token}",
                "Content-Type" to "application/json",
            )

            val text = server.request(
                "POST",
                "/api/v1/text",
                """{"protocolVersion":1,"messageId":"production-text-1","type":"text.send","timestamp":123,"content":"hello"}""",
                headers,
            )
            val responses = listOf(
                server.request("GET", "/diagnostics/health", headers = headers),
                server.request("GET", "/api/v1/trusted-browsers", headers = headers),
                server.request("POST", "/api/v1/files", "{}", headers),
                server.request("POST", "/api/v1/transfer", "{}", headers),
            )

            assertEquals(200, text.statusCode())
            assertTrue(responses.all { it.statusCode() == 404 })
            assertTrue(responses.all { !it.body().contains("token", ignoreCase = true) })
        }

    @Test
    fun productionRuntimeGraphDoesNotReferenceDiagnosticOrTrustedBrowserApi() {
        val runtime = Files.readString(
            Path.of("src/main/java/ru/hznik/devicebridge/data/server/KtorServerRuntimeFactory.kt"),
        )

        assertFalse(runtime.contains("DiagnosticRoutes"))
        assertFalse(runtime.contains("diagnosticToken", ignoreCase = true))
        assertFalse(runtime.contains("TrustedBrowser"))
        assertTrue(runtime.contains("installTextRoutes"))
        assertFalse(runtime.contains("installFileRoutes"))
        assertEquals(true, Files.exists(Path.of("src/debug/java/ru/hznik/devicebridge/diagnostics")))
    }
}
