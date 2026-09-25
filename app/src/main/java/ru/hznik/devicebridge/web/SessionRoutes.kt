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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ru.hznik.devicebridge.core.protocol.session.MAX_SESSION_JSON_BYTES
import ru.hznik.devicebridge.core.protocol.file.FILE_PROTOCOL_VERSION
import ru.hznik.devicebridge.core.protocol.file.FILE_SNAPSHOT_TYPE
import ru.hznik.devicebridge.core.protocol.file.FileProtocolJson
import ru.hznik.devicebridge.core.protocol.file.FileSnapshotEvent
import ru.hznik.devicebridge.core.protocol.session.SESSION_PROTOCOL_VERSION
import ru.hznik.devicebridge.core.protocol.session.SessionChallengeRequest
import ru.hznik.devicebridge.core.protocol.session.SessionChallengeResponse
import ru.hznik.devicebridge.core.protocol.session.SessionConfirmRequest
import ru.hznik.devicebridge.core.protocol.session.SessionConfirmResponse
import ru.hznik.devicebridge.core.protocol.session.SessionConfirmationStatusRequest
import ru.hznik.devicebridge.core.protocol.session.SessionConfirmationStatusResponse
import ru.hznik.devicebridge.core.protocol.session.SessionConfirmationStatusState
import ru.hznik.devicebridge.core.protocol.session.SessionErrorBody
import ru.hznik.devicebridge.core.protocol.session.SessionErrorCode
import ru.hznik.devicebridge.core.protocol.session.SessionErrorEnvelope
import ru.hznik.devicebridge.core.protocol.session.SessionPayloadValidator
import ru.hznik.devicebridge.core.protocol.session.SessionProtocolJson
import ru.hznik.devicebridge.core.protocol.session.SessionStatusResponse
import ru.hznik.devicebridge.core.protocol.session.TrustedSessionExchangeRequest
import ru.hznik.devicebridge.core.protocol.session.SESSION_AUTHENTICATED_MESSAGE_TYPE
import ru.hznik.devicebridge.core.protocol.session.SessionWebSocketAuthMessage
import ru.hznik.devicebridge.core.protocol.session.SessionWebSocketAuthValidator
import ru.hznik.devicebridge.core.protocol.session.SessionWebSocketEventMessage
import ru.hznik.devicebridge.core.protocol.session.SessionWebSocketValidationError
import ru.hznik.devicebridge.core.protocol.session.SessionValidationError
import ru.hznik.devicebridge.core.protocol.text.TEXT_ERROR_TYPE
import ru.hznik.devicebridge.core.protocol.text.TEXT_PROTOCOL_VERSION
import ru.hznik.devicebridge.core.protocol.text.TEXT_RECEIVED_TYPE
import ru.hznik.devicebridge.core.protocol.text.TEXT_SNAPSHOT_TYPE
import ru.hznik.devicebridge.core.protocol.text.TextAcknowledgementMessage
import ru.hznik.devicebridge.core.protocol.text.TextErrorEvent
import ru.hznik.devicebridge.core.protocol.text.TextProtocolErrorCode
import ru.hznik.devicebridge.core.protocol.text.TextProtocolJson
import ru.hznik.devicebridge.core.protocol.text.TextProtocolValidationError
import ru.hznik.devicebridge.core.protocol.text.TextProtocolValidator
import ru.hznik.devicebridge.core.protocol.text.TextReceivedEvent
import ru.hznik.devicebridge.core.protocol.text.TextSnapshotEvent
import ru.hznik.devicebridge.core.protocol.text.TextSnapshotItem
import ru.hznik.devicebridge.data.session.BrowserSessionCoordinator
import ru.hznik.devicebridge.data.file.FileTransferCoordinator
import ru.hznik.devicebridge.data.session.ChallengeCreationResult
import ru.hznik.devicebridge.data.session.PairingRateLimiter
import ru.hznik.devicebridge.data.session.RateLimitDecision
import ru.hznik.devicebridge.data.session.SessionEventConnection
import ru.hznik.devicebridge.data.session.SessionEventDispatcher
import ru.hznik.devicebridge.data.session.SessionGenerationHandle
import ru.hznik.devicebridge.data.session.SessionConfirmationResult
import ru.hznik.devicebridge.data.session.SessionConfirmationRecoveryResult
import ru.hznik.devicebridge.data.session.SessionConnection
import ru.hznik.devicebridge.data.session.SessionOutboundEvent
import ru.hznik.devicebridge.data.session.TrustedSessionExchangeResult
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.data.text.TextTransferCoordinator
import ru.hznik.devicebridge.domain.session.PairingChallengeId
import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferDirection
import ru.hznik.devicebridge.domain.text.TextTransferItem
import ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES
import ru.hznik.devicebridge.domain.file.effectiveFileLimitBytes

fun Application.installSessionRoutes(
    coordinator: BrowserSessionCoordinator,
    generationHandle: () -> SessionGenerationHandle?,
    allowedHosts: () -> Set<String>,
    sourceIpv4: (ApplicationCall) -> String,
    monotonicClockMs: () -> Long,
    wallClockMs: () -> Long,
    webSocketAuthTimeoutMs: Long = 5_000,
    textCoordinator: TextTransferCoordinator? = null,
    eventDispatcher: SessionEventDispatcher? = null,
    fileCoordinator: FileTransferCoordinator? = null,
    effectiveFileLimitBytes: () -> Long = { HARD_MAX_FILE_BYTES },
    deviceName: () -> String = { "DeviceBridge Android" },
) {
    require(webSocketAuthTimeoutMs > 0)
    val trustedExchangeRateLimiter = PairingRateLimiter(
        MonotonicClock { monotonicClockMs() },
    )
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
        maxFrameSize = MAX_SESSION_WEBSOCKET_FRAME_BYTES
        masking = false
    }
    routing {
        post("/api/v1/session/challenge") {
            val (request, handle) = call.receiveSessionRequest<SessionChallengeRequest>(
                allowedHosts(),
                generationHandle,
                invalidMessage = "Недопустимые данные клиента",
                validate = SessionPayloadValidator::validate,
            ) ?: return@post
            when (
                val result = coordinator.createChallenge(
                    handle,
                    request.clientLabel,
                    sourceIpv4(call),
                    request.rememberBrowserRequested,
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
                is ChallengeCreationResult.RateLimited -> call.respondRateLimited(result.retryAfterMs)
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
            val (request, handle) = call.receiveSessionRequest<SessionConfirmRequest>(
                allowedHosts(),
                generationHandle,
                invalidMessage = "Недопустимые данные подтверждения",
                validate = SessionPayloadValidator::validate,
            ) ?: return@post
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
                            trustedCredential = result.trustedCredential,
                            trustedCredentialExpiresAtEpochMillis =
                                result.trustedCredentialExpiresAtEpochMillis,
                        ),
                    ),
                )
                is SessionConfirmationResult.InvalidCode -> call.respondSessionError(
                    HttpStatusCode.Unauthorized,
                    SessionErrorCode.INVALID_CODE,
                    "Неверный код подключения",
                    attemptsRemaining = result.remainingAttempts,
                )
                is SessionConfirmationResult.RateLimited -> call.respondRateLimited(result.retryAfterMs)
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

        post("/api/v1/session/confirmation/status") {
            val (request, handle) =
                call.receiveSessionRequest<SessionConfirmationStatusRequest>(
                    allowedHosts(),
                    generationHandle,
                    invalidMessage = "Недопустимые данные подтверждения",
                    validate = SessionPayloadValidator::validate,
                ) ?: return@post
            when (
                val result = coordinator.recoverConfirmation(
                    handle = handle,
                    challengeId = PairingChallengeId(request.challengeId),
                    browserLabel = request.clientLabel,
                    sourceIpv4 = sourceIpv4(call),
                )
            ) {
                SessionConfirmationRecoveryResult.Pending ->
                    call.respondConfirmationState(SessionConfirmationStatusState.PENDING)
                is SessionConfirmationRecoveryResult.Approved -> call.respondJson(
                    HttpStatusCode.OK,
                    SessionProtocolJson.encode(
                        SessionConfirmationStatusResponse(
                            protocolVersion = SESSION_PROTOCOL_VERSION,
                            state = SessionConfirmationStatusState.APPROVED,
                            sessionId = result.confirmation.sessionId.value,
                            token = result.confirmation.token,
                            serverTimeEpochMillis = wallClockMs(),
                            trustedCredential = result.confirmation.trustedCredential,
                            trustedCredentialExpiresAtEpochMillis =
                                result.confirmation.trustedCredentialExpiresAtEpochMillis,
                        ),
                    ),
                )
                SessionConfirmationRecoveryResult.Denied ->
                    call.respondConfirmationState(SessionConfirmationStatusState.DENIED)
                SessionConfirmationRecoveryResult.Expired ->
                    call.respondConfirmationState(SessionConfirmationStatusState.EXPIRED)
                SessionConfirmationRecoveryResult.InvalidMetadata -> call.respondSessionError(
                    HttpStatusCode.BadRequest,
                    SessionErrorCode.INVALID_PAYLOAD,
                    "Недопустимые данные подтверждения",
                )
                SessionConfirmationRecoveryResult.CapacityReached -> call.respondSessionError(
                    HttpStatusCode.TooManyRequests,
                    SessionErrorCode.CAPACITY_REACHED,
                    "Слишком много активных сессий",
                )
                SessionConfirmationRecoveryResult.GenerationClosed -> call.respondSessionError(
                    HttpStatusCode.ServiceUnavailable,
                    SessionErrorCode.SESSION_CLOSED,
                    "Серверная сессия завершена",
                )
            }
        }

        post("/api/v1/session/trusted") {
            val (request, handle) =
                call.receiveSessionRequest<TrustedSessionExchangeRequest>(
                    allowedHosts(),
                    generationHandle,
                    invalidMessage = "Недопустимые данные доверенного браузера",
                    validate = SessionPayloadValidator::validate,
                ) ?: return@post
            val source = sourceIpv4(call)
            when (val limit = trustedExchangeRateLimiter.check(source)) {
                is RateLimitDecision.Blocked -> {
                    call.respondRateLimited(limit.retryAfterMs)
                    return@post
                }
                is RateLimitDecision.Allowed -> Unit
            }
            when (
                val result = coordinator.exchangeTrusted(
                    handle = handle,
                    rawCredential = request.trustedCredential,
                    sourceIpv4 = source,
                    nowEpochMillis = wallClockMs(),
                )
            ) {
                is TrustedSessionExchangeResult.Approved -> {
                    trustedExchangeRateLimiter.recordSuccess(source)
                    call.respondJson(
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
                }
                TrustedSessionExchangeResult.InvalidCredential -> {
                    when (val failure = trustedExchangeRateLimiter.recordFailure(source)) {
                        is RateLimitDecision.Blocked -> call.respondRateLimited(failure.retryAfterMs)
                        is RateLimitDecision.Allowed -> call.respondSessionError(
                            HttpStatusCode.Unauthorized,
                            SessionErrorCode.UNAUTHORIZED,
                            "Доверенный доступ недействителен или отозван",
                            attemptsRemaining = failure.remainingAttempts,
                        )
                    }
                }
                TrustedSessionExchangeResult.Expired -> {
                    trustedExchangeRateLimiter.recordFailure(source)
                    call.respondSessionError(
                        HttpStatusCode.Gone,
                        SessionErrorCode.EXPIRED,
                        "Срок доверенного доступа истёк",
                    )
                }
                TrustedSessionExchangeResult.InvalidMetadata -> call.respondSessionError(
                    HttpStatusCode.BadRequest,
                    SessionErrorCode.INVALID_PAYLOAD,
                    "Недопустимые данные клиента",
                )
                TrustedSessionExchangeResult.CapacityReached -> call.respondSessionError(
                    HttpStatusCode.TooManyRequests,
                    SessionErrorCode.CAPACITY_REACHED,
                    "Слишком много активных сессий",
                )
                TrustedSessionExchangeResult.GenerationClosed -> call.respondSessionError(
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
                        // Browsers with a live connection; the caller counts even before its
                        // event socket opens, a closed tab does not.
                        activeSessionCount =
                            (coordinator.connectedSessionIds.value + authorized.session.id).size,
                        effectiveFileLimitBytes = effectiveFileLimitBytes(
                            effectiveFileLimitBytes(),
                        ),
                        deviceName = deviceName(),
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
            fileCoordinator?.onSessionRevoked(
                authorized.handle.generationId,
                authorized.session.id,
            )
            coordinator.revoke(authorized.session.id)
            call.respond(HttpStatusCode.NoContent)
        }

        webSocket("/api/v1/events") {
            val security = SessionRequestSecurityPolicy.validateWebSocket(
                host = call.request.header(HttpHeaders.Host),
                origin = call.request.header(HttpHeaders.Origin),
                allowedHosts = allowedHosts(),
                originScheme = call.originScheme(),
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
            val sendMutex = Mutex()
            suspend fun sendSerialized(payload: String) {
                sendMutex.withLock { send(Frame.Text(payload)) }
            }
            val eventConnection = SessionEventConnection { event ->
                val payload = when (event) {
                    is SessionOutboundEvent.Text ->
                        TextProtocolJson.encode(event.item.toReceivedEvent())
                    is SessionOutboundEvent.Control -> event.payload
                    is SessionOutboundEvent.FileProgress -> event.payload
                    is SessionOutboundEvent.FileTerminal -> event.payload
                }
                runCatching { sendSerialized(payload) }.isSuccess
            }
            var eventConnectionAttached = false
            try {
                sendSerialized(
                    SessionProtocolJson.encode(
                        SessionWebSocketEventMessage(
                            protocolVersion = SESSION_PROTOCOL_VERSION,
                            messageId = "server-auth-${auth.messageId}",
                            type = SESSION_AUTHENTICATED_MESSAGE_TYPE,
                            timestamp = wallClockMs(),
                        ),
                    ),
                )
                if (textCoordinator != null) {
                    val snapshot = textCoordinator.snapshotFor(handle.generationId, session.id)
                    sendSerialized(
                        TextProtocolJson.encode(
                            TextSnapshotEvent(
                                protocolVersion = TEXT_PROTOCOL_VERSION,
                                messageId = "server-snapshot-${wallClockMs()}",
                                type = TEXT_SNAPSHOT_TYPE,
                                timestamp = wallClockMs(),
                                items = snapshot.map(TextTransferItem::toSnapshotItem),
                            ),
                        ),
                    )
                }
                if (fileCoordinator != null) {
                    val fileSnapshot = fileCoordinator.snapshotFor(
                        handle.generationId,
                        session.id,
                    )
                    sendSerialized(
                        FileProtocolJson.encode(
                            FileSnapshotEvent(
                                protocolVersion = FILE_PROTOCOL_VERSION,
                                messageId = "server-file-snapshot-${wallClockMs()}",
                                type = FILE_SNAPSHOT_TYPE,
                                timestamp = wallClockMs(),
                                items = fileSnapshot.items.map { it.toSnapshotItem() },
                            ),
                        ),
                    )
                }
                if (eventDispatcher != null) {
                    eventDispatcher.attach(session.id, eventConnection)
                    eventConnectionAttached = true
                }
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val acknowledgement = runCatching {
                        TextProtocolJson.decode<TextAcknowledgementMessage>(frame.readText())
                    }.getOrNull()
                    val validation = acknowledgement?.let(TextProtocolValidator::validate)
                    val accepted = acknowledgement != null &&
                        validation == TextProtocolValidationError.NONE &&
                        acknowledgement.messageId !in seenMessageIds &&
                        textCoordinator?.acknowledge(
                            generationId = handle.generationId,
                            sessionId = session.id,
                            messageId = TextMessageId(acknowledgement.acknowledgedMessageId),
                        ) == true
                    if (accepted) {
                        seenMessageIds += acknowledgement.messageId
                        continue
                    }
                    val errorCode = if (
                        validation == TextProtocolValidationError.UNSUPPORTED_VERSION
                    ) {
                        TextProtocolErrorCode.UNSUPPORTED_VERSION
                    } else {
                        TextProtocolErrorCode.INVALID_PAYLOAD
                    }
                    sendSerialized(
                        TextProtocolJson.encode(
                            TextErrorEvent(
                                protocolVersion = TEXT_PROTOCOL_VERSION,
                                messageId = "server-error-${wallClockMs()}",
                                type = TEXT_ERROR_TYPE,
                                timestamp = wallClockMs(),
                                relatedMessageId = acknowledgement?.messageId,
                                code = errorCode,
                            ),
                        ),
                    )
                    closeSessionPolicy("Invalid text control message")
                    return@webSocket
                }
            } finally {
                if (
                    eventConnectionAttached &&
                    eventDispatcher?.detach(session.id, eventConnection) == true
                ) {
                    textCoordinator?.onConnectionLost(session.id)
                }
                fileCoordinator?.onSessionDisconnected(handle.generationId, session.id)
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

internal data class AuthorizedSession(
    val session: BrowserSession,
    val handle: SessionGenerationHandle,
)

internal suspend fun ApplicationCall.authorizeSession(
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
        originScheme = originScheme(),
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

/**
 * Checks Host, Origin, content type and declared length of a JSON API request and responds when
 * it is rejected. Without [onTooLarge] an oversized body is an ordinary 400 session error; with it
 * the body is rejected as 413 and [onTooLarge] gives the route's own answer.
 */
internal suspend fun ApplicationCall.requireJsonApi(
    allowedHosts: Set<String>,
    maxBodyBytes: Long = MAX_SESSION_JSON_BYTES.toLong(),
    onTooLarge: (suspend () -> Unit)? = null,
): Boolean {
    val result = SessionRequestSecurityPolicy.validateJsonApi(
        host = request.header(HttpHeaders.Host),
        origin = request.header(HttpHeaders.Origin),
        contentType = request.header(HttpHeaders.ContentType),
        contentLength = request.header(HttpHeaders.ContentLength)?.toLongOrNull(),
        allowedHosts = allowedHosts,
        maxBodyBytes = maxBodyBytes,
        bodyTooLargeStatus = if (onTooLarge == null) {
            HttpStatusCode.BadRequest
        } else {
            HttpStatusCode.PayloadTooLarge
        },
        originScheme = originScheme(),
    )
    if (result is RequestGuardResult.Rejected) {
        if (onTooLarge != null && result.status == HttpStatusCode.PayloadTooLarge) {
            onTooLarge()
            return false
        }
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

private data class ReceivedSessionRequest<T>(
    val request: T,
    val handle: SessionGenerationHandle,
)

/**
 * Shared prologue of the JSON session routes. Responds and returns null when the request is
 * rejected or the server session is not active.
 */
private suspend inline fun <reified T : Any> ApplicationCall.receiveSessionRequest(
    allowedHosts: Set<String>,
    generationHandle: () -> SessionGenerationHandle?,
    invalidMessage: String,
    validate: (T) -> SessionValidationError,
): ReceivedSessionRequest<T>? {
    if (!requireJsonApi(allowedHosts)) return null
    val body = receiveBoundedJson()
    if (body == null) {
        respondSessionError(
            HttpStatusCode.BadRequest,
            SessionErrorCode.INVALID_PAYLOAD,
            "Некорректное или слишком большое тело запроса",
        )
        return null
    }
    val request = runCatching {
        SessionProtocolJson.decode<T>(body)
    }.getOrNull()
    if (request == null) {
        respondSessionError(
            HttpStatusCode.BadRequest,
            SessionErrorCode.INVALID_PAYLOAD,
            "Некорректное тело запроса",
        )
        return null
    }
    when (validate(request)) {
        SessionValidationError.UNSUPPORTED_VERSION -> {
            respondSessionError(
                HttpStatusCode.BadRequest,
                SessionErrorCode.UNSUPPORTED_VERSION,
                "Версия протокола не поддерживается",
            )
            return null
        }
        SessionValidationError.NONE -> Unit
        else -> {
            respondSessionError(
                HttpStatusCode.BadRequest,
                SessionErrorCode.INVALID_PAYLOAD,
                invalidMessage,
            )
            return null
        }
    }
    val handle = generationHandle()
    if (handle == null) {
        respondSessionError(
            HttpStatusCode.ServiceUnavailable,
            SessionErrorCode.SESSION_CLOSED,
            "Серверная сессия не активна",
        )
        return null
    }
    return ReceivedSessionRequest(request, handle)
}

private suspend fun ApplicationCall.respondRateLimited(retryAfterMs: Long) {
    respondSessionError(
        HttpStatusCode.TooManyRequests,
        SessionErrorCode.RATE_LIMITED,
        "Слишком много попыток",
        retryAfterSeconds = retryAfterMs.ceilSeconds(),
        attemptsRemaining = 0,
    )
}

private suspend fun ApplicationCall.respondConfirmationState(state: SessionConfirmationStatusState) {
    respondJson(
        HttpStatusCode.OK,
        SessionProtocolJson.encode(
            SessionConfirmationStatusResponse(
                protocolVersion = SESSION_PROTOCOL_VERSION,
                state = state,
            ),
        ),
    )
}

internal suspend fun ApplicationCall.receiveBoundedJson(
    maxBytes: Int = MAX_SESSION_JSON_BYTES,
): String? {
    val channel = receiveChannel()
    val output = ByteArrayOutputStream(maxBytes.coerceAtMost(16 * 1024))
    val buffer = ByteArray(1_024)
    var total = 0
    while (true) {
        val count = channel.readAvailable(buffer, 0, buffer.size)
        if (count < 0) break
        if (count == 0) continue
        total += count
        if (total > maxBytes) return null
        output.write(buffer, 0, count)
    }
    return output.toString(StandardCharsets.UTF_8.name())
}

internal suspend fun ApplicationCall.respondSessionError(
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

internal suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) {
    respondText(body, ContentType.Application.Json, status)
}

private fun Long.ceilSeconds(): Int = ((this + 999) / 1_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private const val MAX_SESSION_WEBSOCKET_FRAME_BYTES = 8 * 1_024L

private fun TextTransferItem.toReceivedEvent(): TextReceivedEvent = TextReceivedEvent(
    protocolVersion = TEXT_PROTOCOL_VERSION,
    messageId = id.value,
    type = TEXT_RECEIVED_TYPE,
    timestamp = createdAtEpochMillis,
    content = content,
    contentKind = contentKind.toDto(),
    direction = direction.toDto(),
    senderLabel = browserLabel,
    status = status.toDto(),
)

private fun TextTransferItem.toSnapshotItem(): TextSnapshotItem = TextSnapshotItem(
    messageId = id.value,
    timestamp = createdAtEpochMillis,
    content = content,
    contentKind = contentKind.toDto(),
    direction = direction.toDto(),
    senderLabel = browserLabel,
    status = status.toDto(),
)

private fun TextTransferDirection.toDto() =
    when (this) {
        TextTransferDirection.ANDROID_TO_BROWSER ->
            ru.hznik.devicebridge.core.protocol.text.TextDirectionDto.ANDROID_TO_BROWSER
        TextTransferDirection.BROWSER_TO_ANDROID ->
            ru.hznik.devicebridge.core.protocol.text.TextDirectionDto.BROWSER_TO_ANDROID
    }
