package ru.hznik.devicebridge.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import ru.hznik.devicebridge.core.protocol.session.MAX_SESSION_JSON_BYTES
import ru.hznik.devicebridge.core.protocol.session.SESSION_PROTOCOL_VERSION
import ru.hznik.devicebridge.core.protocol.session.SessionChallengeRequest
import ru.hznik.devicebridge.core.protocol.session.SessionChallengeResponse
import ru.hznik.devicebridge.core.protocol.session.SessionConfirmRequest
import ru.hznik.devicebridge.core.protocol.session.SessionConfirmResponse
import ru.hznik.devicebridge.core.protocol.session.SessionErrorBody
import ru.hznik.devicebridge.core.protocol.session.SessionErrorCode
import ru.hznik.devicebridge.core.protocol.session.SessionErrorEnvelope
import ru.hznik.devicebridge.core.protocol.session.SessionPayloadValidator
import ru.hznik.devicebridge.core.protocol.session.SessionProtocolJson
import ru.hznik.devicebridge.core.protocol.session.SessionStatusResponse
import ru.hznik.devicebridge.core.protocol.session.SESSION_AUTHENTICATED_MESSAGE_TYPE
import ru.hznik.devicebridge.core.protocol.session.SessionWebSocketAuthMessage
import ru.hznik.devicebridge.core.protocol.session.SessionWebSocketAuthValidator
import ru.hznik.devicebridge.core.protocol.session.SessionWebSocketEventMessage
import ru.hznik.devicebridge.core.protocol.session.SessionWebSocketValidationError
import ru.hznik.devicebridge.core.protocol.session.SessionValidationError
import ru.hznik.devicebridge.data.session.BrowserSessionCoordinator
import ru.hznik.devicebridge.data.session.ChallengeCreationResult
import ru.hznik.devicebridge.data.session.SessionGenerationHandle
import ru.hznik.devicebridge.data.session.SessionConfirmationResult
import ru.hznik.devicebridge.data.session.SessionConnection
import ru.hznik.devicebridge.domain.session.PairingChallengeId
import ru.hznik.devicebridge.domain.session.BrowserSession
import kotlinx.coroutines.withTimeoutOrNull

fun Application.installSessionRoutes(
    coordinator: BrowserSessionCoordinator,
    generationHandle: () -> SessionGenerationHandle?,
    allowedHosts: () -> Set<String>,
    sourceIpv4: (ApplicationCall) -> String,
    monotonicClockMs: () -> Long,
    wallClockMs: () -> Long,
    webSocketAuthTimeoutMs: Long = 5_000,
) {
    require(webSocketAuthTimeoutMs > 0)
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
        maxFrameSize = MAX_SESSION_WEBSOCKET_FRAME_BYTES
        masking = false
    }
    routing {
        post("/api/v1/session/challenge") {
            if (!call.requireJsonApiRequest(allowedHosts())) return@post
            val body = call.receiveBoundedJson()
            if (body == null) {
                call.respondSessionError(
                    HttpStatusCode.BadRequest,
                    SessionErrorCode.INVALID_PAYLOAD,
                    "Некорректное или слишком большое тело запроса",
                )
                return@post
            }
            val request = runCatching {
                SessionProtocolJson.decode<SessionChallengeRequest>(body)
            }.getOrNull()
            if (request == null) {
                call.respondSessionError(
                    HttpStatusCode.BadRequest,
                    SessionErrorCode.INVALID_PAYLOAD,
                    "Некорректное тело запроса",
                )
                return@post
            }
            when (SessionPayloadValidator.validate(request)) {
                SessionValidationError.UNSUPPORTED_VERSION -> {
                    call.respondSessionError(
                        HttpStatusCode.BadRequest,
                        SessionErrorCode.UNSUPPORTED_VERSION,
                        "Версия протокола не поддерживается",
                    )
                    return@post
                }
                SessionValidationError.NONE -> Unit
                else -> {
                    call.respondSessionError(
                        HttpStatusCode.BadRequest,
                        SessionErrorCode.INVALID_PAYLOAD,
                        "Недопустимые данные клиента",
                    )
                    return@post
                }
            }
            val handle = generationHandle()
            if (handle == null) {
                call.respondSessionError(
                    HttpStatusCode.ServiceUnavailable,
                    SessionErrorCode.SESSION_CLOSED,
                    "Серверная сессия не активна",
                )
                return@post
            }
            when (
                val result = coordinator.createChallenge(
                    handle,
                    request.clientLabel,
                    sourceIpv4(call),
                )
            ) {
                is ChallengeCreationResult.Created -> {
                    val remainingMs = (
                        result.expiresAtElapsedRealtimeMs - monotonicClockMs()
                    ).coerceAtLeast(0)
                    call.respondJson(
                        HttpStatusCode.OK,
                        SessionProtocolJson.encode(
                            SessionChallengeResponse(
                                protocolVersion = SESSION_PROTOCOL_VERSION,
                                challengeId = result.challengeId.value,
                                expiresAtEpochMillis = Math.addExact(wallClockMs(), remainingMs),
                                confirmTimeoutSeconds = result.confirmTimeoutSeconds,
                                attemptsRemaining = result.attemptsRemaining,
                            ),
                        ),
                    )
                }
                is ChallengeCreationResult.RateLimited -> call.respondSessionError(
                    HttpStatusCode.TooManyRequests,
                    SessionErrorCode.RATE_LIMITED,
                    "Слишком много попыток",
                    retryAfterSeconds = result.retryAfterMs.ceilSeconds(),
                    attemptsRemaining = 0,
                )
                ChallengeCreationResult.InvalidMetadata -> call.respondSessionError(
                    HttpStatusCode.BadRequest,
                    SessionErrorCode.INVALID_PAYLOAD,
                    "Недопустимые данные клиента",
                )
                ChallengeCreationResult.CapacityReached -> call.respondSessionError(
                    HttpStatusCode.TooManyRequests,
                    SessionErrorCode.CAPACITY_REACHED,
                    "Слишком много активных запросов",
                )
                ChallengeCreationResult.GenerationClosed -> call.respondSessionError(
                    HttpStatusCode.ServiceUnavailable,
                    SessionErrorCode.SESSION_CLOSED,
                    "Серверная сессия завершена",
                )
            }
        }

        post("/api/v1/session/confirm") {
            if (!call.requireJsonApiRequest(allowedHosts())) return@post
            val body = call.receiveBoundedJson()
            if (body == null) {
                call.respondSessionError(
                    HttpStatusCode.BadRequest,
                    SessionErrorCode.INVALID_PAYLOAD,
                    "Некорректное или слишком большое тело запроса",
                )
                return@post
            }
            val request = runCatching {
                SessionProtocolJson.decode<SessionConfirmRequest>(body)
            }.getOrNull()
            if (request == null) {
                call.respondSessionError(
                    HttpStatusCode.BadRequest,
                    SessionErrorCode.INVALID_PAYLOAD,
                    "Некорректное тело запроса",
                )
                return@post
            }
            when (SessionPayloadValidator.validate(request)) {
                SessionValidationError.UNSUPPORTED_VERSION -> {
                    call.respondSessionError(
                        HttpStatusCode.BadRequest,
                        SessionErrorCode.UNSUPPORTED_VERSION,
                        "Версия протокола не поддерживается",
                    )
                    return@post
                }
                SessionValidationError.NONE -> Unit
                else -> {
                    call.respondSessionError(
                        HttpStatusCode.BadRequest,
                        SessionErrorCode.INVALID_PAYLOAD,
                        "Недопустимые данные подтверждения",
                    )
                    return@post
                }
            }
            val handle = generationHandle()
            if (handle == null) {
                call.respondSessionError(
                    HttpStatusCode.ServiceUnavailable,
                    SessionErrorCode.SESSION_CLOSED,
                    "Серверная сессия не активна",
                )
                return@post
            }
            val result = coordinator.confirmAndAwait(
                handle = handle,
                challengeId = PairingChallengeId(request.challengeId),
                code = request.code,
                browserLabel = request.clientLabel,
                sourceIpv4 = sourceIpv4(call),
            )
            when (result) {
                is SessionConfirmationResult.Approved -> call.respondJson(
                    HttpStatusCode.OK,
                    SessionProtocolJson.encode(
                        SessionConfirmResponse(
                            protocolVersion = SESSION_PROTOCOL_VERSION,
                            sessionId = result.sessionId.value,
                            token = result.token,
                            serverTimeEpochMillis = wallClockMs(),
                        ),
                    ),
                )
                is SessionConfirmationResult.InvalidCode -> call.respondSessionError(
                    HttpStatusCode.Unauthorized,
                    SessionErrorCode.INVALID_CODE,
                    "Неверный код подключения",
                    attemptsRemaining = result.remainingAttempts,
                )
                is SessionConfirmationResult.RateLimited -> call.respondSessionError(
                    HttpStatusCode.TooManyRequests,
                    SessionErrorCode.RATE_LIMITED,
                    "Слишком много попыток",
                    retryAfterSeconds = result.retryAfterMs.ceilSeconds(),
                    attemptsRemaining = 0,
                )
                SessionConfirmationResult.InvalidChallenge,
                SessionConfirmationResult.InvalidMetadata,
                -> call.respondSessionError(
                    HttpStatusCode.BadRequest,
                    SessionErrorCode.INVALID_PAYLOAD,
                    "Недопустимый запрос подтверждения",
                )
                SessionConfirmationResult.Expired -> call.respondSessionError(
                    HttpStatusCode.Gone,
                    SessionErrorCode.EXPIRED,
                    "Код или запрос истёк",
                )
                SessionConfirmationResult.Denied -> call.respondSessionError(
                    HttpStatusCode.Forbidden,
                    SessionErrorCode.DENIED,
                    "Подключение отклонено на телефоне",
                )
                SessionConfirmationResult.TimedOut -> call.respondSessionError(
                    HttpStatusCode.RequestTimeout,
                    SessionErrorCode.EXPIRED,
                    "Время подтверждения истекло",
                )
                SessionConfirmationResult.CapacityReached -> call.respondSessionError(
                    HttpStatusCode.TooManyRequests,
                    SessionErrorCode.CAPACITY_REACHED,
                    "Слишком много ожидающих запросов",
                )
                SessionConfirmationResult.GenerationClosed -> call.respondSessionError(
                    HttpStatusCode.ServiceUnavailable,
                    SessionErrorCode.SESSION_CLOSED,
                    "Серверная сессия завершена",
                )
            }
        }

        get("/api/v1/status") {
            val authorized = call.authorizeSession(
                coordinator,
                generationHandle,
                allowedHosts,
                allowMissingOrigin = true,
            ) ?: return@get
            call.respondJson(
                HttpStatusCode.OK,
                SessionProtocolJson.encode(
                    SessionStatusResponse(
                        protocolVersion = SESSION_PROTOCOL_VERSION,
                        sessionId = authorized.session.id.value,
                        connected = true,
                        activeSessionCount = coordinator.state.value.sessions.size,
                    ),
                ),
            )
        }

        delete("/api/v1/session") {
            val authorized = call.authorizeSession(
                coordinator,
                generationHandle,
                allowedHosts,
            ) ?: return@delete
            coordinator.revoke(authorized.session.id)
            call.respond(HttpStatusCode.NoContent)
        }

        webSocket("/api/v1/events") {
            val security = SessionRequestSecurityPolicy.validateWebSocket(
                host = call.request.header(HttpHeaders.Host),
                origin = call.request.header(HttpHeaders.Origin),
                allowedHosts = allowedHosts(),
            )
            if (security is RequestGuardResult.Rejected) {
                closeSessionPolicy("Origin or Host rejected")
                return@webSocket
            }
            val handle = generationHandle()
            if (handle == null) {
                closeSessionPolicy("Generation closed")
                return@webSocket
            }
            val first = withTimeoutOrNull(webSocketAuthTimeoutMs) { incoming.receive() }
            if (first !is Frame.Text) {
                closeSessionPolicy("Authentication required")
                return@webSocket
            }
            val auth = runCatching {
                SessionProtocolJson.decode<SessionWebSocketAuthMessage>(first.readText())
            }.getOrNull()
            val seenMessageIds = linkedSetOf<String>()
            if (
                auth == null ||
                SessionWebSocketAuthValidator.validate(auth, seenMessageIds) !=
                SessionWebSocketValidationError.NONE
            ) {
                closeSessionPolicy("Invalid authentication message")
                return@webSocket
            }
            val session = coordinator.authenticate(handle, auth.token)
            if (session == null) {
                closeSessionPolicy("Unauthorized")
                return@webSocket
            }
            seenMessageIds += auth.messageId
            val connection = SessionConnection {
                closeSessionPolicy("Session revoked")
            }
            if (!coordinator.attachConnection(session.id, connection)) {
                closeSessionPolicy("Session revoked")
                return@webSocket
            }
            try {
                send(
                    Frame.Text(
                        SessionProtocolJson.encode(
                            SessionWebSocketEventMessage(
                                protocolVersion = SESSION_PROTOCOL_VERSION,
                                messageId = "server-auth-${auth.messageId}",
                                type = SESSION_AUTHENTICATED_MESSAGE_TYPE,
                                timestamp = wallClockMs(),
                            ),
                        ),
                    ),
                )
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val repeated = runCatching {
                        SessionProtocolJson.decode<SessionWebSocketAuthMessage>(frame.readText())
                    }.getOrNull()
                    val validation = repeated?.let {
                        SessionWebSocketAuthValidator.validate(it, seenMessageIds)
                    }
                    closeSessionPolicy(
                        if (validation == SessionWebSocketValidationError.DUPLICATE_MESSAGE_ID) {
                            "Duplicate authentication message"
                        } else {
                            "Unexpected control message"
                        },
                    )
                    return@webSocket
                }
            } finally {
                coordinator.detachConnection(session.id, connection)
            }
        }
    }
}

private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.closeSessionPolicy(
    message: String,
) {
    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, message))
}

private data class AuthorizedSession(
    val session: BrowserSession,
    val handle: SessionGenerationHandle,
)

private suspend fun ApplicationCall.authorizeSession(
    coordinator: BrowserSessionCoordinator,
    generationHandle: () -> SessionGenerationHandle?,
    allowedHosts: () -> Set<String>,
    allowMissingOrigin: Boolean = false,
): AuthorizedSession? {
    val security = SessionRequestSecurityPolicy.validateProtectedHttp(
        host = request.header(HttpHeaders.Host),
        origin = request.header(HttpHeaders.Origin),
        allowedHosts = allowedHosts(),
        allowMissingOrigin = allowMissingOrigin,
    )
    if (security is RequestGuardResult.Rejected) {
        respondSessionError(
            security.status,
            SessionErrorCode.INVALID_PAYLOAD,
            "Запрос отклонён политикой локального источника",
        )
        return null
    }
    val handle = generationHandle()
    val token = SessionBearerAuthorization.parse(
        request.headers.getAll(HttpHeaders.Authorization).orEmpty(),
    )
    if (handle == null || token == null) {
        respondSessionError(
            HttpStatusCode.Unauthorized,
            SessionErrorCode.UNAUTHORIZED,
            "Требуется действующая browser session",
        )
        return null
    }
    val session = coordinator.authenticate(handle, token)
    if (session == null) {
        respondSessionError(
            HttpStatusCode.Unauthorized,
            SessionErrorCode.UNAUTHORIZED,
            "Требуется действующая browser session",
        )
        return null
    }
    return AuthorizedSession(session, handle)
}

private suspend fun ApplicationCall.requireJsonApiRequest(allowedHosts: Set<String>): Boolean {
    val result = SessionRequestSecurityPolicy.validateJsonApi(
        host = request.header(HttpHeaders.Host),
        origin = request.header(HttpHeaders.Origin),
        contentType = request.header(HttpHeaders.ContentType),
        contentLength = request.header(HttpHeaders.ContentLength)?.toLongOrNull(),
        allowedHosts = allowedHosts,
    )
    if (result is RequestGuardResult.Rejected) {
        respondSessionError(
            status = result.status,
            code = SessionErrorCode.INVALID_PAYLOAD,
            message = if (result.status == HttpStatusCode.Forbidden) {
                "Запрос отклонён политикой локального источника"
            } else {
                "Некорректный запрос"
            },
        )
        return false
    }
    return true
}

private suspend fun ApplicationCall.receiveBoundedJson(): String? {
    val channel = receiveChannel()
    val output = ByteArrayOutputStream(MAX_SESSION_JSON_BYTES)
    val buffer = ByteArray(1_024)
    var total = 0
    while (true) {
        val count = channel.readAvailable(buffer, 0, buffer.size)
        if (count < 0) break
        if (count == 0) continue
        total += count
        if (total > MAX_SESSION_JSON_BYTES) return null
        output.write(buffer, 0, count)
    }
    return output.toString(StandardCharsets.UTF_8.name())
}

private suspend fun ApplicationCall.respondSessionError(
    status: HttpStatusCode,
    code: SessionErrorCode,
    message: String,
    retryAfterSeconds: Int? = null,
    attemptsRemaining: Int? = null,
) {
    respondJson(
        status,
        SessionProtocolJson.encode(
            SessionErrorEnvelope(
                SessionErrorBody(code, message, retryAfterSeconds, attemptsRemaining),
            ),
        ),
    )
}

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) {
    respondText(body, ContentType.Application.Json, status)
}

private fun Long.ceilSeconds(): Int = ((this + 999) / 1_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private const val MAX_SESSION_WEBSOCKET_FRAME_BYTES = 8 * 1_024L
