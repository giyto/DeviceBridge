package ru.hznik.devicebridge.data.server

import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.network.LanAddressCandidate
import ru.hznik.devicebridge.data.network.LanEndpointResolver
import ru.hznik.devicebridge.data.network.LanNetworkSnapshot
import ru.hznik.devicebridge.data.network.LanNetworkSnapshotProvider
import ru.hznik.devicebridge.web.AllowlistedWebAssetProvider
import ru.hznik.devicebridge.web.WebAssetDescriptor
import ru.hznik.devicebridge.web.WebAssetSource

class KtorServerRuntimeFactoryTest {

    @Test
    fun productionFactoryLivesInMainAndKeepsDiagnosticsInDebug() {
        val mainSource = Path.of(
            "src/main/java/ru/hznik/devicebridge/data/server/KtorServerRuntimeFactory.kt",
        )
        val debugDiagnostics = Path.of(
            "src/debug/java/ru/hznik/devicebridge/diagnostics/server/DiagnosticRoutes.kt",
        )

        assertTrue(Files.exists(mainSource))
        assertTrue(Files.exists(debugDiagnostics))
        val source = Files.readString(mainSource)
        assertTrue(source.contains("ServerRuntimeFactory"))
        assertFalse(source.contains("DiagnosticRoutes"))
        assertFalse(source.contains("token", ignoreCase = true))
    }

    @Test
    fun bindsDynamicallyPublishesLanEndpointAndReleasesPort() = runBlocking {
        val runtime = factory().create()

        val endpoint = runtime.start()
        try {
            assertEquals(LAN_HOST, endpoint.host)
            assertTrue(endpoint.port in 1..65_535)
            assertEquals("wlan0|" + LAN_HOST, runtime.networkFingerprint)
            assertTrue(
                rawGet(
                    port = endpoint.port,
                    hostHeader = endpoint.authority,
                    path = "/",
                ).startsWith("HTTP/1.1 200"),
            )
        } finally {
            runtime.stop()
        }

        ServerSocket(endpoint.port).use { rebound ->
            assertTrue(rebound.isBound)
        }
    }

    @Test
    fun productionRuntimeDoesNotPublishDiagnosticsOrFutureApi() = runBlocking {
        val runtime = factory().create()
        val endpoint = runtime.start()

        try {
            val diagnostics = rawGet(endpoint.port, endpoint.authority, "/diagnostics/health")
            val futureApi = rawGet(endpoint.port, endpoint.authority, "/api/v1/status")

            assertTrue(diagnostics.startsWith("HTTP/1.1 404"))
            assertTrue(futureApi.startsWith("HTTP/1.1 404"))
            assertFalse(diagnostics.contains("ktorVersion"))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun twentyProductionRuntimeCyclesServeManifestAndReleasePort() = runBlocking {
        repeat(20) {
            val runtime = factory().create()
            val endpoint = runtime.start()

            val response = rawGet(
                port = endpoint.port,
                hostHeader = endpoint.authority,
                path = "/web-manifest.json",
            )
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("\"protocolVersion\":1"))

            runtime.stop()
            ServerSocket(endpoint.port).use { rebound ->
                assertTrue(rebound.isBound)
            }
        }
    }

    private fun factory(): KtorServerRuntimeFactory =
        KtorServerRuntimeFactory(
            networkSnapshotProvider = LanNetworkSnapshotProvider {
                LanNetworkSnapshot(
                    activeWifiAddresses = listOf(
                        LanAddressCandidate(
                            host = LAN_HOST,
                            interfaceName = "wlan0",
                            isUp = true,
                        ),
                    ),
                )
            },
            endpointResolver = LanEndpointResolver(),
            webAssetProvider = AllowlistedWebAssetProvider(
                allowedPaths = FILES.keys,
                source = object : WebAssetSource {
                    override fun describe(path: String): WebAssetDescriptor? {
                        val bytes = FILES[path] ?: return null
                        return WebAssetDescriptor(
                            length = bytes.size.toLong(),
                            openStream = { ByteArrayInputStream(bytes) },
                        )
                    }
                },
            ),
        )

    private fun rawGet(
        port: Int,
        hostHeader: String,
        path: String,
    ): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 2_000
        val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.US_ASCII)
        writer.write("GET " + path + " HTTP/1.1\r\n")
        writer.write("Host: " + hostHeader + "\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.flush()
        socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
    }

    private val ru.hznik.devicebridge.domain.model.ServerEndpoint.authority: String
        get() = host + ":" + port

    private companion object {
        const val LAN_HOST = "192.168.1.24"
        val FILES = mapOf(
            "index.html" to "<!doctype html><title>DeviceBridge</title>".encodeToByteArray(),
            "asset-manifest.json" to "{}".encodeToByteArray(),
            "web-manifest.json" to
                "{\"protocolVersion\":1,\"webAssetVersion\":\"sha256-test\"}"
                    .encodeToByteArray(),
        )
    }
}
