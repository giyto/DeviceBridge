package ru.hznik.devicebridge.data.server

import java.io.ByteArrayInputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.network.LanAddressCandidate
import ru.hznik.devicebridge.data.network.LanEndpointResolver
import ru.hznik.devicebridge.data.network.LanNetworkSnapshot
import ru.hznik.devicebridge.data.network.LanNetworkSnapshotProvider
import ru.hznik.devicebridge.data.session.BrowserSessionCoordinator
import ru.hznik.devicebridge.data.session.SessionEventConnection
import ru.hznik.devicebridge.data.session.SessionEventDispatcher
import ru.hznik.devicebridge.data.session.security.CryptographicRandom
import ru.hznik.devicebridge.data.session.security.SessionSecretGenerator
import ru.hznik.devicebridge.data.text.TextTransferCoordinator
import ru.hznik.devicebridge.data.file.CompletedFileRegistry
import ru.hznik.devicebridge.data.file.FileDownloadSourceFactory
import ru.hznik.devicebridge.data.file.FileSourceRegistry
import ru.hznik.devicebridge.data.file.FileTransferCoordinator
import ru.hznik.devicebridge.data.file.FileUploadTargetFactory
import ru.hznik.devicebridge.data.file.FileDestinationLeaseRegistry
import ru.hznik.devicebridge.data.file.ScopedDocumentTreeLease
import ru.hznik.devicebridge.data.file.DocumentTreePermissionGateway
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingCodeState
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.session.BrowserSessionPhase
import ru.hznik.devicebridge.domain.text.IncomingTextRequest
import ru.hznik.devicebridge.domain.text.SendTextRequest
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferRejection
import ru.hznik.devicebridge.domain.text.TextTransferResult
import ru.hznik.devicebridge.web.AllowlistedWebAssetProvider
import ru.hznik.devicebridge.web.WebAssetDescriptor
import ru.hznik.devicebridge.web.WebAssetSource
import ru.hznik.devicebridge.web.FileSessionEventBridge

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
        val eventDispatcher = SessionEventDispatcher(scope = scope)
        val textSession = BrowserSession(
            id = BrowserSessionId("session-1"),
            generationId = ServerGenerationId(1),
            browserLabel = "Chrome",
            sourceIpv4 = "192.168.1.2",
            connectedAtElapsedRealtimeMs = 1_000,
        )
        val textBrowserState = BrowserSessionState.active(
            generationId = ServerGenerationId(1),
            pairingCode = PairingCodeState("123456", 60_000),
            sessions = listOf(textSession),
        )
        val textTransfers = TextTransferCoordinator(
            nowEpochMillis = { 1_000_000 },
            browserSessionState = { textBrowserState },
            eventGateway = eventDispatcher,
        )
        val fileTransfers = FileTransferCoordinator(
            browserSessionState = { textBrowserState },
        )
        val fileSources = FileSourceRegistry()
        val completedFiles = CompletedFileRegistry()
        val destinationLeases = FileDestinationLeaseRegistry()
        val releasedLeases = mutableListOf<String>()
        val autoAcceptEvents = mutableListOf<String>()
        val trackedId = ru.hznik.devicebridge.domain.file.FileTransferId("tracked-file")
        fileSources.register(trackedId, "content://source")
        completedFiles.register(trackedId, "content://completed")
        destinationLeases.register(
            trackedId,
            ScopedDocumentTreeLease(
                "content://destination",
                3,
                object : DocumentTreePermissionGateway {
                    override fun acquire(uri: String, grantFlags: Int) = true
                    override fun isAvailable(uri: String) = true
                    override fun release(uri: String, grantFlags: Int) { releasedLeases += uri }
                },
            ),
        )
        val runtime = KtorServerRuntimeFactory(
            networkSnapshotProvider = networkProvider(),
            endpointResolver = LanEndpointResolver(),
            webAssetProvider = webAssets(),
            browserSessionCoordinator = sessions,
            textTransferCoordinator = textTransfers,
            sessionEventDispatcher = eventDispatcher,
            fileTransferCoordinator = fileTransfers,
            uploadTargetFactory = FileUploadTargetFactory { _, _ -> error("not used") },
            downloadSourceFactory = FileDownloadSourceFactory { error("not used") },
            fileSourceRegistry = fileSources,
            completedFileRegistry = completedFiles,
            destinationLeaseRegistry = destinationLeases,
            fileSessionEventBridge = FileSessionEventBridge(
                scope = scope,
                coordinator = fileTransfers,
                dispatcher = eventDispatcher,
                wallClockMs = { 1_000_000 },
            ),
            monotonicClock = clock,
            autoAccept = object : ru.hznik.devicebridge.data.file.AutoAcceptLifecycle {
                override fun activate() { autoAcceptEvents += "activate" }
                override fun deactivate() { autoAcceptEvents += "deactivate" }
            },
        ).create()
        val endpoint = runtime.start()
        try {
            val before = challenge(endpoint.port, endpoint.host, endpoint.port)
            assertEquals(503, before.statusCode())
            assertEquals(BrowserSessionPhase.INACTIVE, sessions.state.value.phase)

            assertTrue(autoAcceptEvents.isEmpty())
            runtime.activateSessionGeneration(1)
            assertEquals(listOf("activate"), autoAcceptEvents)

            val active = challenge(endpoint.port, endpoint.host, endpoint.port)
            assertEquals(200, active.statusCode())
            assertTrue(active.body().contains("\"challengeId\""))
            assertFalse(active.body().contains("123456"))
            val accepted = textTransfers.acceptIncoming(
                IncomingTextRequest(
                    id = TextMessageId("runtime-message"),
                    generationId = ServerGenerationId(1),
                    sessionId = BrowserSessionId("session-1"),
                    browserLabel = "Chrome",
                    content = "ephemeral",
                    requestedAtEpochMillis = 999_000,
                ),
            )
            assertTrue(accepted is TextTransferResult.Accepted)
            assertEquals(1, textTransfers.state.value.items.size)
            eventDispatcher.attach(textSession.id, SessionEventConnection { true })
            val pending = async {
                textTransfers.send(SendTextRequest(textSession.id, "pending until stop"))
            }
            while (textTransfers.state.value.items.none { it.content == "pending until stop" }) {
                yield()
            }
            assertFalse(pending.isCompleted)

            runtime.closeSessionGeneration()
            assertEquals(listOf("activate", "deactivate"), autoAcceptEvents)
            assertEquals(BrowserSessionPhase.INACTIVE, sessions.state.value.phase)
            assertTrue(textTransfers.state.value.items.isEmpty())
            assertEquals(null, fileSources.sourceUri(trackedId))
            assertEquals(null, completedFiles.uri(trackedId))
            assertEquals(listOf("content://destination"), releasedLeases)
            assertEquals(
                TextTransferResult.Rejected(TextTransferRejection.GENERATION_CLOSED),
                pending.await(),
            )
            assertEquals(
                TextTransferResult.Rejected(TextTransferRejection.GENERATION_CLOSED),
                textTransfers.acceptIncoming(
                    IncomingTextRequest(
                        id = TextMessageId("stale-message"),
                        generationId = ServerGenerationId(1),
                        sessionId = BrowserSessionId("session-1"),
                        browserLabel = "Chrome",
                        content = "must not arrive",
                        requestedAtEpochMillis = 1_000_000,
                    ),
                ),
            )
            assertEquals(503, challenge(endpoint.port, endpoint.host, endpoint.port).statusCode())
        } finally {
            runtime.stop()
            scope.cancel()
        }
        assertEquals(listOf("activate", "deactivate"), autoAcceptEvents)
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
