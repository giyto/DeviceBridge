package ru.hznik.devicebridge.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import ru.hznik.devicebridge.core.protocol.session.SessionErrorCode
import ru.hznik.devicebridge.core.protocol.text.MAX_TEXT_JSON_BYTES
import ru.hznik.devicebridge.core.protocol.text.TEXT_ACCEPTED_TYPE
import ru.hznik.devicebridge.core.protocol.text.TEXT_ERROR_TYPE
import ru.hznik.devicebridge.core.protocol.text.TEXT_PROTOCOL_VERSION
import ru.hznik.devicebridge.core.protocol.text.TextAcceptedResponse
import ru.hznik.devicebridge.core.protocol.text.TextContentKindDto
import ru.hznik.devicebridge.core.protocol.text.TextErrorEvent
import ru.hznik.devicebridge.core.protocol.text.TextProtocolErrorCode
import ru.hznik.devicebridge.core.protocol.text.TextProtocolJson
import ru.hznik.devicebridge.core.protocol.text.TextProtocolValidationError
import ru.hznik.devicebridge.core.protocol.text.TextProtocolValidator
import ru.hznik.devicebridge.core.protocol.text.TextSendRequest
import ru.hznik.devicebridge.core.protocol.text.TextTransferStatusDto
import ru.hznik.devicebridge.data.session.BrowserSessionCoordinator
import ru.hznik.devicebridge.data.session.SessionGenerationHandle
import ru.hznik.devicebridge.data.text.TextTransferCoordinator
import ru.hznik.devicebridge.domain.text.IncomingTextRequest
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferRejection
import ru.hznik.devicebridge.domain.text.TextTransferResult
import ru.hznik.devicebridge.domain.text.TextTransferStatus

fun Application.installTextRoutes(
    sessionCoordinator: BrowserSessionCoordinator,
    textCoordinator: TextTransferCoordinator,
    generationHandle: () -> SessionGenerationHandle?,
    allowedHosts: () -> Set<String>,
    wallClockMs: () -> Long,
) {
    routing {
        post("/api/v1/text") {
            val authorized = call.authorizeSession(
                coordinator = sessionCoordinator,
                generationHandle = generationHandle,
                allowedHosts = allowedHosts,
            ) ?: return@post
            if (!call.requireTextJsonRequest(allowedHosts(), wallClockMs)) return@post

            val body = call.receiveBoundedJson(MAX_TEXT_JSON_BYTES)
            if (body == null) {
                call.respondTextError(
                    status = HttpStatusCode.PayloadTooLarge,
                    code = TextProtocolErrorCode.CONTENT_TOO_LARGE,
                    wallClockMs = wallClockMs,
                )
                return@post
            }
            val request = runCatching { TextProtocolJson.decode<TextSendRequest>(body) }.getOrNull()
            if (request == null) {
                call.respondTextError(
                    status = HttpStatusCode.BadRequest,
                    code = TextProtocolErrorCode.INVALID_PAYLOAD,
                    wallClockMs = wallClockMs,
                )
                return@post
            }
            when (TextProtocolValidator.validate(request)) {
                TextProtocolValidationError.NONE -> Unit
                TextProtocolValidationError.CONTENT_TOO_LARGE -> {
                    call.respondTextError(
                        status = HttpStatusCode.PayloadTooLarge,
                        code = TextProtocolErrorCode.CONTENT_TOO_LARGE,
                        wallClockMs = wallClockMs,
                        relatedMessageId = request.messageId.takeIf(::isValidRelatedMessageId),
                    )
                    return@post
                }
                TextProtocolValidationError.UNSUPPORTED_VERSION -> {
                    call.respondTextError(
                        status = HttpStatusCode.BadRequest,
                        code = TextProtocolErrorCode.UNSUPPORTED_VERSION,
                        wallClockMs = wallClockMs,
                        relatedMessageId = request.messageId.takeIf(::isValidRelatedMessageId),
                    )
                    return@post
                }
                else -> {
                    call.respondTextError(
                        status = HttpStatusCode.BadRequest,
                        code = TextProtocolErrorCode.INVALID_PAYLOAD,
                        wallClockMs = wallClockMs,
                        relatedMessageId = request.messageId.takeIf(::isValidRelatedMessageId),
                    )
                    return@post
                }
            }

            val result = textCoordinator.acceptIncoming(
                IncomingTextRequest(
                    id = TextMessageId(request.messageId),
                    generationId = authorized.handle.generationId,
                    sessionId = authorized.session.id,
                    browserLabel = authorized.session.browserLabel,
                    content = request.content,
                    requestedAtEpochMillis = request.timestamp,
                ),
            )
            when (result) {
                is TextTransferResult.Accepted -> call.respondJson(
                    HttpStatusCode.OK,
                    TextProtocolJson.encode(
                        TextAcceptedResponse(
                            protocolVersion = TEXT_PROTOCOL_VERSION,
                            messageId = result.item.id.value,
                            type = TEXT_ACCEPTED_TYPE,
                            timestamp = result.item.createdAtEpochMillis,
                            contentKind = result.item.contentKind.toDto(),
                            status = result.item.status.toDto(),
                        ),
                    ),
                )
                is TextTransferResult.Rejected -> call.respondTextRejection(
                    result.reason,
                    request.messageId,
                    wallClockMs,
                )
            }
        }
    }
}

private suspend fun ApplicationCall.requireTextJsonRequest(
    allowedHosts: Set<String>,
    wallClockMs: () -> Long,
): Boolean {
    val result = SessionRequestSecurityPolicy.validateJsonApi(
        host = request.header(HttpHeaders.Host),
        origin = request.header(HttpHeaders.Origin),
        contentType = request.header(HttpHeaders.ContentType),
        contentLength = request.header(HttpHeaders.ContentLength)?.toLongOrNull(),
        allowedHosts = allowedHosts,
        maxBodyBytes = MAX_TEXT_JSON_BYTES.toLong(),
        bodyTooLargeStatus = HttpStatusCode.PayloadTooLarge,
    )
    if (result is RequestGuardResult.Rejected) {
        if (result.status == HttpStatusCode.PayloadTooLarge) {
            respondTextError(
                status = result.status,
                code = TextProtocolErrorCode.CONTENT_TOO_LARGE,
                wallClockMs = wallClockMs,
            )
        } else {
            respondSessionError(
                status = result.status,
                code = SessionErrorCode.INVALID_PAYLOAD,
                message = if (result.status == HttpStatusCode.Forbidden) {
                    "Запрос отклонён политикой локального источника"
                } else {
                    "Некорректный запрос"
                },
            )
        }
        return false
    }
    return true
}

private suspend fun ApplicationCall.respondTextRejection(
    rejection: TextTransferRejection,
    relatedMessageId: String,
    wallClockMs: () -> Long,
) {
    val (status, code) = when (rejection) {
        TextTransferRejection.CONTENT_TOO_LARGE ->
            HttpStatusCode.PayloadTooLarge to TextProtocolErrorCode.CONTENT_TOO_LARGE
        TextTransferRejection.MESSAGE_CONFLICT ->
            HttpStatusCode.Conflict to TextProtocolErrorCode.MESSAGE_CONFLICT
        TextTransferRejection.GENERATION_CLOSED,
        TextTransferRejection.SESSION_UNAVAILABLE,
        -> HttpStatusCode.ServiceUnavailable to TextProtocolErrorCode.SESSION_UNAVAILABLE
        TextTransferRejection.EMPTY_CONTENT,
        TextTransferRejection.MESSAGE_NOT_FOUND,
        -> HttpStatusCode.BadRequest to TextProtocolErrorCode.INVALID_PAYLOAD
    }
    respondTextError(status, code, wallClockMs, relatedMessageId)
}

private suspend fun ApplicationCall.respondTextError(
    status: HttpStatusCode,
    code: TextProtocolErrorCode,
    wallClockMs: () -> Long,
    relatedMessageId: String? = null,
) {
    respondJson(
        status,
        TextProtocolJson.encode(
            TextErrorEvent(
                protocolVersion = TEXT_PROTOCOL_VERSION,
                messageId = "server-error",
                type = TEXT_ERROR_TYPE,
                timestamp = wallClockMs(),
                relatedMessageId = relatedMessageId,
                code = code,
            ),
        ),
    )
}

private fun isValidRelatedMessageId(value: String): Boolean =
    value.length in 1..64 && value.matches(Regex("^[A-Za-z0-9_-]+$"))

internal fun TextContentKind.toDto(): TextContentKindDto = when (this) {
    TextContentKind.TEXT -> TextContentKindDto.TEXT
    TextContentKind.LINK -> TextContentKindDto.LINK
}

internal fun TextTransferStatus.toDto(): TextTransferStatusDto = when (this) {
    TextTransferStatus.PENDING -> TextTransferStatusDto.PENDING
    TextTransferStatus.SENDING -> TextTransferStatusDto.SENDING
    TextTransferStatus.DELIVERED -> TextTransferStatusDto.DELIVERED
    TextTransferStatus.FAILED -> TextTransferStatusDto.FAILED
}
