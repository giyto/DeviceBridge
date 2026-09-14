package ru.hznik.devicebridge.web

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal fun <T> withWebRouteServer(
    files: Map<String, ByteArray> = defaultWebFiles(),
    block: (port: Int, request: (String, Map<String, String>) -> HttpResponse<ByteArray>) -> T,
): T {
    val provider = AllowlistedWebAssetProvider(
        allowedPaths = files.keys,
        source = TestMapWebAssetSource(files),
    )
    val port = ServerSocket(0).use { it.localPort }
    val host = "127.0.0.1:$port"
    val engine = embeddedServer(
        factory = CIO,
        host = "127.0.0.1",
        port = port,
        module = {
            installWebRoutes(
                webAssetProvider = provider,
                allowedHosts = setOf(host),
            )
        },
    )
    engine.start(wait = false)
    val client = HttpClient.newHttpClient()

    return try {
        block(port) { path, headers ->
            val builder = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:$port$path"))
                .GET()
            headers.forEach(builder::header)
            client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        }
    } finally {
        engine.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
    }
}

internal fun defaultWebFiles(): Map<String, ByteArray> = mapOf(
    "index.html" to "<!doctype html><title>DeviceBridge</title>".encodeToByteArray(),
    "asset-manifest.json" to "{}".encodeToByteArray(),
    "web-manifest.json" to
        """{"protocolVersion":1,"webAssetVersion":"sha256-0123456789abcdef"}"""
            .encodeToByteArray(),
    "assets/index-A1b2C3d4.js" to "export{}".encodeToByteArray(),
    "assets/index-E5f6G7h8.css" to ":root{}".encodeToByteArray(),
)

private class TestMapWebAssetSource(
    private val files: Map<String, ByteArray>,
) : WebAssetSource {
    override fun describe(path: String): WebAssetDescriptor? {
        val bytes = files[path] ?: return null
        return WebAssetDescriptor(
            length = bytes.size.toLong(),
            openStream = { ByteArrayInputStream(bytes) },
        )
    }
}
