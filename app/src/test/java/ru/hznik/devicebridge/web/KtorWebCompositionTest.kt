package ru.hznik.devicebridge.web

import java.io.ByteArrayInputStream
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
import ru.hznik.devicebridge.diagnostics.server.KtorCioRuntimeFactory
import ru.hznik.devicebridge.diagnostics.server.ManagedEmbeddedServerController
import ru.hznik.devicebridge.diagnostics.server.ServerState

class KtorWebCompositionTest {

    @Test
    fun debugCioServesWebWhileDiagnosticsRemainProtected() = runBlocking {
        val files = defaultWebFiles()
        val provider = AllowlistedWebAssetProvider(
            allowedPaths = files.keys,
            source = object : WebAssetSource {
                override fun describe(path: String): WebAssetDescriptor? {
                    val bytes = files[path] ?: return null
                    return WebAssetDescriptor(
                        length = bytes.size.toLong(),
                        openStream = { ByteArrayInputStream(bytes) },
                    )
                }
            },
        )
        val controller = ManagedEmbeddedServerController(
            KtorCioRuntimeFactory(
                webAssetProvider = provider,
                webHostNames = setOf("127.0.0.1"),
            ),
        )
        val port = ServerSocket(0).use { it.localPort }
        controller.start(port)
        val running = controller.state.value as ServerState.Running

        try {
            val root = get(port, "/")
            val manifest = get(port, "/web-manifest.json")
            val missingStatus = get(port, "/api/v1/status")
            val diagnosticsWithoutToken = get(port, "/diagnostics/health")
            val diagnosticsWithToken = get(
                port,
                "/diagnostics/health",
                authorization = "Bearer ${running.token}",
            )

            assertEquals(200, root.statusCode())
            assertTrue(root.body().contains("DeviceBridge"))
            assertEquals(200, manifest.statusCode())
            assertEquals(404, missingStatus.statusCode())
            assertEquals(401, diagnosticsWithoutToken.statusCode())
            assertFalse(diagnosticsWithoutToken.body().contains("ktorVersion"))
            assertEquals(200, diagnosticsWithToken.statusCode())
            assertTrue(diagnosticsWithToken.body().contains("\"ktorVersion\":\"3.5.2\""))
        } finally {
            controller.stop()
        }
    }

    private fun get(
        port: Int,
        path: String,
        authorization: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$port$path"))
            .GET()
        authorization?.let { builder.header("Authorization", it) }
        return HttpClient.newHttpClient().send(
            builder.build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }
}
