package ru.hznik.devicebridge.diagnostics.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.hznik.devicebridge.diagnostics.stream.DEFAULT_DIAGNOSTIC_CHUNK_BYTES
import ru.hznik.devicebridge.diagnostics.stream.DEFAULT_DIAGNOSTIC_PAYLOAD_BYTES
import ru.hznik.devicebridge.diagnostics.stream.DiagnosticPayloadGenerator
import ru.hznik.devicebridge.diagnostics.stream.StreamingSha256

private val diagnosticJson = Json {
    encodeDefaults = true
}

@Serializable
data class DiagnosticHealthResponse(
    val status: String,
    val ktorVersion: String,
    val engine: String,
    val sdkInt: Int,
    val uptimeMs: Long,
)

@Serializable
data class DiagnosticEchoMessage(
    val id: Int,
    val payload: String,
)

@Serializable
data class DiagnosticUploadResponse(
    val bytesReceived: Long,
    val sha256: String,
)

fun Application.installDiagnosticRoutes(
    token: String,
    sdkInt: Int,
    startedAtMillis: Long,
    clockMillis: () -> Long,
) {
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
        maxFrameSize = MAX_DIAGNOSTIC_FRAME_BYTES
        masking = false
    }

    routing {
        route("/diagnostics") {
            get("/health") {
                if (!call.hasBearerToken(token)) {
                    call.respondText(
                        text = "{\"error\":\"unauthorized\"}",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.Unauthorized,
                    )
                    return@get
                }

                val response = DiagnosticHealthResponse(
                    status = "ok",
                    ktorVersion = KTOR_VERSION,
                    engine = ENGINE_NAME,
                    sdkInt = sdkInt,
                    uptimeMs = (clockMillis() - startedAtMillis).coerceAtLeast(0),
                )
                call.respondText(
                    text = diagnosticJson.encodeToString(response),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                )
            }

            webSocket("/ws") {
                if (!call.hasBearerToken(token)) {
                    close(
                        CloseReason(
                            code = CloseReason.Codes.VIOLATED_POLICY,
                            message = "Unauthorized",
                        ),
                    )
                    return@webSocket
                }

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val message = diagnosticJson.decodeFromString<DiagnosticEchoMessage>(
                            frame.readText(),
                        )
                        send(
                            Frame.Text(diagnosticJson.encodeToString(message)),
                        )
                    }
                }
            }

            post("/upload") {
                if (!call.hasBearerToken(token)) {
                    call.respondText(
                        text = "{\"error\":\"unauthorized\"}",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.Unauthorized,
                    )
                    return@post
                }

                val channel = call.receiveChannel()
                val buffer = ByteArray(DEFAULT_DIAGNOSTIC_CHUNK_BYTES)
                val digest = StreamingSha256()
                while (true) {
                    val count = channel.readAvailable(
                        buffer = buffer,
                        offset = 0,
                        length = buffer.size,
                    )
                    if (count < 0) {
                        break
                    }
                    if (count > 0) {
                        digest.update(buffer, offset = 0, length = count)
                    }
                }

                call.respondText(
                    text = diagnosticJson.encodeToString(
                        DiagnosticUploadResponse(
                            bytesReceived = digest.bytesProcessed,
                            sha256 = digest.digestHex(),
                        ),
                    ),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                )
            }

            get("/download") {
                if (!call.hasBearerToken(token)) {
                    call.respondText(
                        text = "{\"error\":\"unauthorized\"}",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.Unauthorized,
                    )
                    return@get
                }

                val requestedBytes = call.request.queryParameters["bytes"]
                    ?.toLongOrNull()
                    ?: DEFAULT_DIAGNOSTIC_PAYLOAD_BYTES
                if (requestedBytes !in 0..DEFAULT_DIAGNOSTIC_PAYLOAD_BYTES) {
                    call.respondText(
                        text = "{\"error\":\"invalid_size\"}",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.BadRequest,
                    )
                    return@get
                }

                val generator = DiagnosticPayloadGenerator(
                    totalBytes = requestedBytes,
                    chunkSize = DEFAULT_DIAGNOSTIC_CHUNK_BYTES,
                )
                call.response.header("X-Content-Bytes", requestedBytes.toString())
                call.response.header("X-Content-SHA256", generator.sha256())
                call.respondBytesWriter(
                    contentType = ContentType.Application.OctetStream,
                    contentLength = requestedBytes,
                    status = HttpStatusCode.OK,
                ) {
                    val buffer = ByteArray(DEFAULT_DIAGNOSTIC_CHUNK_BYTES)
                    var offset = 0L
                    while (offset < requestedBytes) {
                        val count = generator.read(offset, buffer)
                        writeFully(buffer, startIndex = 0, endIndex = count)
                        offset += count
                    }
                }
            }
        }
    }
}

fun ApplicationCall.hasBearerToken(expectedToken: String): Boolean {
    return request.header(HttpHeaders.Authorization) == "Bearer $expectedToken"
}

private const val KTOR_VERSION = "3.5.2"
private const val ENGINE_NAME = "CIO"
private const val MAX_DIAGNOSTIC_FRAME_BYTES = 1_048_576L
