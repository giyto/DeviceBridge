package ru.hznik.devicebridge.web

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.data.file.FileTransferCoordinator
import ru.hznik.devicebridge.data.file.FileUploadTargetFactory
import ru.hznik.devicebridge.data.file.DownloadGrantRegistry
import ru.hznik.devicebridge.data.file.FileDownloadSourceFactory
import ru.hznik.devicebridge.data.session.BrowserSessionCoordinator
import ru.hznik.devicebridge.data.session.SessionEventDispatcher
import ru.hznik.devicebridge.data.session.SessionGenerationHandle
import ru.hznik.devicebridge.data.text.TextTransferCoordinator
import ru.hznik.devicebridge.data.session.security.CryptographicRandom
import ru.hznik.devicebridge.data.session.security.SessionSecretGenerator
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.core.protocol.session.SessionChallengeResponse
import ru.hznik.devicebridge.core.protocol.session.SessionConfirmResponse
import ru.hznik.devicebridge.core.protocol.session.SessionProtocolJson

internal class SessionRouteTestServer(
    maxChallenges: Int = 64,
    confirmWaitTimeoutMs: Long = 60_000,
    webSocketAuthTimeoutMs: Long = 5_000,
    uploadTargetFactory: FileUploadTargetFactory? = null,
    downloadSourceFactory: FileDownloadSourceFactory? = null,
    enableFileEvents: Boolean = false,
    effectiveFileLimitBytes: Long = ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES,
    effectiveFileLimitProvider: (() -> Long)? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = FixedClock(1_000)
    private var grantNowEpochMillis = 1_000_000L
    val trustedBrowserRepository = TestTrustedBrowserRepository()
    val coordinator = BrowserSessionCoordinator(
        clock = clock,
        secretGenerator = SessionSecretGenerator(DeterministicRandom()),
        scope = scope,
        maxChallenges = maxChallenges,
        confirmWaitTimeoutMs = confirmWaitTimeoutMs,
        trustedBrowserRepository = trustedBrowserRepository,
        wallClock = Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC),
    )
    val handle: SessionGenerationHandle = runBlocking {
        coordinator.activate(ServerGenerationId(1))
    }
    val eventDispatcher = SessionEventDispatcher(scope = scope)
    val fileCoordinator = FileTransferCoordinator(
        browserSessionState = { coordinator.state.value },
        downloadGrantRegistry = DownloadGrantRegistry(
            nowEpochMillis = { grantNowEpochMillis },
        ),
    ).also { files ->
        runBlocking { files.activate(handle.generationId) }
    }
    val textCoordinator = TextTransferCoordinator(
        nowEpochMillis = { 1_000_000 },
        browserSessionState = { coordinator.state.value },
        eventGateway = eventDispatcher,
    ).also { text ->
        runBlocking { text.activate(handle.generationId) }
    }
    private val fileEventBridge = if (enableFileEvents) {
        FileSessionEventBridge(
            scope = scope,
            coordinator = fileCoordinator,
            dispatcher = eventDispatcher,
            wallClockMs = { 1_000_000 },
        )
    } else {
        null
    }
    val port: Int = ServerSocket(0).use { it.localPort }
    val authority: String = "127.0.0.1:$port"
    private val engine = embeddedServer(
        factory = CIO,
        host = "127.0.0.1",
        port = port,
        module = {
            installSessionRoutes(
                coordinator = coordinator,
                generationHandle = { handle },
                allowedHosts = { setOf(authority) },
                sourceIpv4 = { "127.0.0.1" },
                monotonicClockMs = clock::nowMs,
                wallClockMs = { 1_000_000 },
                webSocketAuthTimeoutMs = webSocketAuthTimeoutMs,
                textCoordinator = textCoordinator,
                eventDispatcher = eventDispatcher,
                fileCoordinator = fileCoordinator.takeIf { enableFileEvents },
                effectiveFileLimitBytes = {
                    effectiveFileLimitProvider?.invoke() ?: effectiveFileLimitBytes
                },
            )
            installTextRoutes(
                sessionCoordinator = coordinator,
                textCoordinator = textCoordinator,
                generationHandle = { handle },
                allowedHosts = { setOf(authority) },
                wallClockMs = { 1_000_000 },
            )
            installFileRoutes(
                sessionCoordinator = coordinator,
                fileCoordinator = fileCoordinator,
                generationHandle = { handle },
                allowedHosts = { setOf(authority) },
                wallClockMs = { 1_000_000 },
                uploadTargetFactory = uploadTargetFactory,
                downloadSourceFactory = downloadSourceFactory,
                effectiveFileLimitBytes = {
                    effectiveFileLimitProvider?.invoke() ?: effectiveFileLimitBytes
                },
            )
        },
    ).also { it.start(wait = false) }
    private val client = HttpClient.newHttpClient()

    fun request(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$port$path"))
        headers.forEach(builder::header)
        when (method) {
            "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body.orEmpty()))
            "DELETE" -> builder.DELETE()
            else -> builder.GET()
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun requestAsync(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): java.util.concurrent.CompletableFuture<HttpResponse<String>> {
        val builder = HttpRequest.newBuilder().uri(URI("http://127.0.0.1:$port$path"))
        headers.forEach(builder::header)
        when (method) {
            "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body.orEmpty()))
            "DELETE" -> builder.DELETE()
            else -> builder.GET()
        }
        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun requestBytes(
        method: String,
        path: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder().uri(URI("http://127.0.0.1:$port$path"))
        headers.forEach(builder::header)
        if (method == "POST") builder.POST(HttpRequest.BodyPublishers.ofByteArray(body))
        else error("Unsupported byte request method: $method")
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun advanceClockTo(value: Long) {
        clock.value = value
    }

    fun advanceGrantClockTo(value: Long) {
        grantNowEpochMillis = value
    }

    fun pairBrowser(label: String): SessionConfirmResponse {
        val challengeResponse = request(
            "POST",
            "/api/v1/session/challenge",
            """{"protocolVersion":1,"clientLabel":"$label"}""",
            sameOriginJsonHeaders(),
        )
        val challenge = SessionProtocolJson.decode<SessionChallengeResponse>(challengeResponse.body())
        val code = requireNotNull(coordinator.state.value.pairingCode).value
        val confirmation = requestAsync(
            "POST",
            "/api/v1/session/confirm",
            """{"protocolVersion":1,"challengeId":"${challenge.challengeId}","code":"$code","clientLabel":"$label"}""",
            sameOriginJsonHeaders(),
        )
        repeat(100) {
            val pending = coordinator.state.value.pendingRequests.singleOrNull()
            if (pending != null) {
                runBlocking { coordinator.approve(pending.id) }
                return SessionProtocolJson.decode(confirmation.get().body())
            }
            Thread.sleep(10)
        }
        error("Pending request was not published")
    }

    fun pairTrustedBrowser(label: String): SessionConfirmResponse {
        val challengeResponse = request(
            "POST",
            "/api/v1/session/challenge",
            """{"protocolVersion":1,"clientLabel":"$label","rememberBrowserRequested":true}""",
            sameOriginJsonHeaders(),
        )
        val challenge = SessionProtocolJson.decode<SessionChallengeResponse>(challengeResponse.body())
        val code = requireNotNull(coordinator.state.value.pairingCode).value
        val confirmation = requestAsync(
            "POST",
            "/api/v1/session/confirm",
            """{"protocolVersion":1,"challengeId":"${challenge.challengeId}","code":"$code","clientLabel":"$label"}""",
            sameOriginJsonHeaders(),
        )
        repeat(100) {
            val pending = coordinator.state.value.pendingRequests.singleOrNull()
            if (pending != null) {
                runBlocking { coordinator.approveAndRemember(pending.id) }
                return SessionProtocolJson.decode(confirmation.get().body())
            }
            Thread.sleep(10)
        }
        error("Pending trusted request was not published")
    }

    fun sameOriginJsonHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "Origin" to "http://$authority",
            "Content-Type" to "application/json",
        ) + extra

    fun sameOriginBinaryHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "Origin" to "http://$authority",
            "Content-Type" to "application/octet-stream",
        ) + extra

    override fun close() {
        fileEventBridge?.close()
        runBlocking { fileCoordinator.close(handle.generationId) }
        runBlocking { textCoordinator.close(handle.generationId) }
        runBlocking { coordinator.closeGeneration(handle) }
        engine.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
        scope.cancel()
    }

    private class FixedClock(var value: Long) : MonotonicClock {
        override fun nowMs(): Long = value
    }

    private class DeterministicRandom : CryptographicRandom {
        private val codes = generateSequence(123456) { 654321 }.iterator()
        private var seed = 1
        override fun nextInt(bound: Int): Int = codes.next()
        override fun nextBytes(size: Int): ByteArray = ByteArray(size) { (seed++).toByte() }
    }
}

internal inline fun <T> withSessionRouteServer(
    maxChallenges: Int = 64,
    confirmWaitTimeoutMs: Long = 60_000,
    webSocketAuthTimeoutMs: Long = 5_000,
    uploadTargetFactory: FileUploadTargetFactory? = null,
    downloadSourceFactory: FileDownloadSourceFactory? = null,
    enableFileEvents: Boolean = false,
    effectiveFileLimitBytes: Long = ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES,
    noinline effectiveFileLimitProvider: (() -> Long)? = null,
    block: (SessionRouteTestServer) -> T,
): T = SessionRouteTestServer(
    maxChallenges,
    confirmWaitTimeoutMs,
    webSocketAuthTimeoutMs,
    uploadTargetFactory,
    downloadSourceFactory,
    enableFileEvents,
    effectiveFileLimitBytes,
    effectiveFileLimitProvider,
).use(block)
