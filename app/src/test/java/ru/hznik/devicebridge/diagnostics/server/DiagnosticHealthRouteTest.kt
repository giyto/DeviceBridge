package ru.hznik.devicebridge.diagnostics.server

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticHealthRouteTest {

    @Test
    fun healthRequiresBearerTokenAndReturnsDiagnosticJson() = runBlocking {
        val controller = ManagedEmbeddedServerController(KtorCioRuntimeFactory())
        val port = findFreePort()
        controller.start(port)
        val running = controller.state.value as ServerState.Running

        try {
            val authorized = requestHealth(port, "Bearer " + running.token)
            val missing = requestHealth(port, authorization = null)
            val invalid = requestHealth(port, "Bearer wrong-token")

            assertEquals(200, authorized.statusCode())
            assertTrue(
                authorized.headers()
                    .firstValue("content-type")
                    .orElse("")
                    .contains("application/json"),
            )
            assertTrue(authorized.body().contains("\"status\":\"ok\""))
            assertTrue(authorized.body().contains("\"ktorVersion\":\"3.5.2\""))
            assertTrue(authorized.body().contains("\"engine\":\"CIO\""))
            assertTrue(authorized.body().contains("\"sdkInt\":"))
            assertTrue(authorized.body().contains("\"uptimeMs\":"))

            assertEquals(401, missing.statusCode())
            assertEquals(401, invalid.statusCode())
            assertFalse(missing.body().contains("ktorVersion"))
            assertFalse(invalid.body().contains("ktorVersion"))
        } finally {
            controller.stop()
        }
    }

    private fun requestHealth(
        port: Int,
        authorization: String?,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$port/diagnostics/health"))
            .GET()
        if (authorization != null) {
            builder.header("Authorization", authorization)
        }
        return HttpClient.newHttpClient().send(
            builder.build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}
