package ru.hznik.devicebridge.web

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.data.session.BrowserSessionCoordinator
import ru.hznik.devicebridge.data.session.SessionGenerationHandle
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
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = FixedClock(1_000)
    val coordinator = BrowserSessionCoordinator(
        clock = clock,
        secretGenerator = SessionSecretGenerator(DeterministicRandom()),
        scope = scope,
        maxChallenges = maxChallenges,
        confirmWaitTimeoutMs = confirmWaitTimeoutMs,
    )
    val handle: SessionGenerationHandle = runBlocking {
        coordinator.activate(ServerGenerationId(1))
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

    fun advanceClockTo(value: Long) {
        clock.value = value
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

    fun sameOriginJsonHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "Origin" to "http://$authority",
            "Content-Type" to "application/json",
        ) + extra

    override fun close() {
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
    block: (SessionRouteTestServer) -> T,
): T = SessionRouteTestServer(
    maxChallenges,
    confirmWaitTimeoutMs,
    webSocketAuthTimeoutMs,
).use(block)
