package ru.hznik.devicebridge.data.server

import java.io.ByteArrayInputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.network.LanAddressCandidate
import ru.hznik.devicebridge.data.network.LanEndpointResolver
import ru.hznik.devicebridge.data.network.LanNetworkSnapshot
import ru.hznik.devicebridge.data.network.LanNetworkSnapshotProvider
import ru.hznik.devicebridge.data.session.BrowserSessionCoordinator
import ru.hznik.devicebridge.data.session.security.CryptographicRandom
import ru.hznik.devicebridge.data.session.security.SessionSecretGenerator
import ru.hznik.devicebridge.domain.session.BrowserSessionPhase
import ru.hznik.devicebridge.web.AllowlistedWebAssetProvider
import ru.hznik.devicebridge.web.WebAssetDescriptor
import ru.hznik.devicebridge.web.WebAssetSource

class KtorServerRuntimeSessionTest {

    @Test
    fun listenerExistsBeforeGenerationAndRoutesUseOnlyActivatedHandle() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = MonotonicClock { 1_000 }
        val sessions = BrowserSessionCoordinator(
            clock,
            SessionSecretGenerator(DeterministicRandom()),
            scope,
        )
        val runtime = KtorServerRuntimeFactory(
            networkSnapshotProvider = networkProvider(),
            endpointResolver = LanEndpointResolver(),
            webAssetProvider = webAssets(),
            browserSessionCoordinator = sessions,
            monotonicClock = clock,
        ).create()
        val endpoint = runtime.start()
        try {
            val before = challenge(endpoint.port, endpoint.host, endpoint.port)
            assertEquals(503, before.statusCode())
            assertEquals(BrowserSessionPhase.INACTIVE, sessions.state.value.phase)

            runtime.activateSessionGeneration(1)

            val active = challenge(endpoint.port, endpoint.host, endpoint.port)
            assertEquals(200, active.statusCode())
            assertTrue(active.body().contains("\"challengeId\""))
            assertFalse(active.body().contains("123456"))

            runtime.closeSessionGeneration()
            assertEquals(BrowserSessionPhase.INACTIVE, sessions.state.value.phase)
            assertEquals(503, challenge(endpoint.port, endpoint.host, endpoint.port).statusCode())
        } finally {
            runtime.stop()
            scope.cancel()
        }
    }

    private fun challenge(listenerPort: Int, host: String, port: Int): RawResponse {
        val body = """{"protocolVersion":1,"clientLabel":"Chrome"}"""
        val raw = Socket("127.0.0.1", listenerPort).use { socket ->
            socket.soTimeout = 2_000
            val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.US_ASCII)
            writer.write("POST /api/v1/session/challenge HTTP/1.1\r\n")
            writer.write("Host: $host:$port\r\n")
            writer.write("Origin: http://$host:$port\r\n")
            writer.write("Content-Type: application/json\r\n")
            writer.write("Content-Length: ${body.toByteArray().size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.write(body)
            writer.flush()
            socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }
        val status = raw.lineSequence().first().split(' ')[1].toInt()
        return RawResponse(status, raw.substringAfter("\r\n\r\n"))
    }

    private data class RawResponse(private val status: Int, private val content: String) {
        fun statusCode(): Int = status
        fun body(): String = content
    }

    private fun networkProvider() = LanNetworkSnapshotProvider {
        LanNetworkSnapshot(
            activeWifiAddresses = listOf(
                LanAddressCandidate("192.168.1.24", "wlan0", true),
            ),
        )
    }

    private fun webAssets(): AllowlistedWebAssetProvider {
        val files = mapOf(
            "index.html" to "<!doctype html>".encodeToByteArray(),
            "web-manifest.json" to "{}".encodeToByteArray(),
        )
        return AllowlistedWebAssetProvider(
            files.keys,
            object : WebAssetSource {
                override fun describe(path: String): WebAssetDescriptor? {
                    val bytes = files[path] ?: return null
                    return WebAssetDescriptor(bytes.size.toLong()) { ByteArrayInputStream(bytes) }
                }
            },
        )
    }

    private class DeterministicRandom : CryptographicRandom {
        private var seed = 1
        override fun nextInt(bound: Int): Int = 123456
        override fun nextBytes(size: Int): ByteArray = ByteArray(size) { (seed++).toByte() }
    }
}
