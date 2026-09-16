package ru.hznik.devicebridge.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import ru.hznik.devicebridge.core.protocol.file.FILE_ERROR_TYPE
import ru.hznik.devicebridge.core.protocol.file.FILE_DOWNLOAD_GRANT_TYPE
import ru.hznik.devicebridge.core.protocol.file.FILE_PROTOCOL_VERSION
import ru.hznik.devicebridge.core.protocol.file.FILE_SNAPSHOT_TYPE
import ru.hznik.devicebridge.core.protocol.file.FileDirectionDto
import ru.hznik.devicebridge.core.protocol.file.FileDownloadGrantRequest
import ru.hznik.devicebridge.core.protocol.file.FileDownloadGrantResponse
import ru.hznik.devicebridge.core.protocol.file.FileErrorEvent
import ru.hznik.devicebridge.core.protocol.file.FileMetadataDto
import ru.hznik.devicebridge.core.protocol.file.FileOfferMessage
import ru.hznik.devicebridge.core.protocol.file.FileProtocolErrorCode
import ru.hznik.devicebridge.core.protocol.file.FileProtocolJson
import ru.hznik.devicebridge.core.protocol.file.FileProtocolValidationError
import ru.hznik.devicebridge.core.protocol.file.FileProtocolValidator
import ru.hznik.devicebridge.core.protocol.file.FileSnapshotEvent
import ru.hznik.devicebridge.core.protocol.file.FileSnapshotItem
import ru.hznik.devicebridge.core.protocol.file.FileTransferStatusDto
import ru.hznik.devicebridge.core.protocol.file.FileVerificationMessage
import ru.hznik.devicebridge.core.protocol.session.SessionErrorCode
import ru.hznik.devicebridge.data.file.FileTransferCoordinator
import ru.hznik.devicebridge.data.file.FileDownloadSourceFactory
import ru.hznik.devicebridge.data.file.FileTransferResources
import ru.hznik.devicebridge.data.file.FileUploadTarget
import ru.hznik.devicebridge.data.file.FileUploadTargetFactory
import ru.hznik.devicebridge.data.session.BrowserSessionCoordinator
import ru.hznik.devicebridge.data.session.SessionGenerationHandle
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileBatchValidation
import ru.hznik.devicebridge.domain.file.FileCommandId
import ru.hznik.devicebridge.domain.file.FileMetadataCandidate
import ru.hznik.devicebridge.domain.file.FileMetadataError
import ru.hznik.devicebridge.domain.file.FileMetadataValidator
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferEvent
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.FileTransferState
import ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES
import ru.hznik.devicebridge.domain.file.SafeFilenameResolver
import ru.hznik.devicebridge.domain.file.VerifyFileTransferRequest

private const val MAX_FILE_CONTROL_JSON_BYTES = 64 * 1024

fun Application.installFileRoutes(
    sessionCoordinator: BrowserSessionCoordinator,
    fileCoordinator: FileTransferCoordinator,
    generationHandle: () -> SessionGenerationHandle?,
    allowedHosts: () -> Set<String>,
    wallClockMs: () -> Long,
    uploadTargetFactory: FileUploadTargetFactory? = null,
    uploadProcessor: RawFileUploadProcessor = RawFileUploadProcessor(),
    downloadSourceFactory: FileDownloadSourceFactory? = null,
) {
    routing {
        post("/api/v1/files") {
            val authorized = call.authorizeSession(
                coordinator = sessionCoordinator,
                generationHandle = generationHandle,
                allowedHosts = allowedHosts,
            ) ?: return@post
            if (!call.requireFileJsonRequest(allowedHosts(), wallClockMs)) return@post

            val body = call.receiveBoundedJson(MAX_FILE_CONTROL_JSON_BYTES)
            if (body == null) {
                call.respondFileError(HttpStatusCode.PayloadTooLarge, FileProtocolErrorCode.INVALID_PAYLOAD, wallClockMs)
                return@post
            }
            val offer = runCatching { FileProtocolJson.decode<FileOfferMessage>(body) }.getOrNull()
            if (offer == null) {
                call.respondFileError(HttpStatusCode.BadRequest, FileProtocolErrorCode.INVALID_PAYLOAD, wallClockMs)
                return@post
            }
            when (FileProtocolValidator.validate(offer)) {
                FileProtocolValidationError.NONE -> Unit
                FileProtocolValidationError.UNSUPPORTED_VERSION -> {
                    call.respondFileError(HttpStatusCode.BadRequest, FileProtocolErrorCode.UNSUPPORTED_VERSION, wallClockMs, offer.messageId)
                    return@post
                }
                else -> {
                    call.respondFileError(HttpStatusCode.BadRequest, FileProtocolErrorCode.INVALID_PAYLOAD, wallClockMs, offer.messageId)
                    return@post
                }
            }
            if (offer.items.any { it.direction != FileDirectionDto.BROWSER_TO_ANDROID }) {
                call.respondFileError(HttpStatusCode.BadRequest, FileProtocolErrorCode.INVALID_PAYLOAD, wallClockMs, offer.messageId)
                return@post
            }
            val validation = FileMetadataValidator.validateBatch(offer.items.map { it.toCandidate() })
            val metadata = when (validation) {
                is FileBatchValidation.Valid -> validation.items
                is FileBatchValidation.InvalidItem -> {
                    val tooLarge = validation.error == FileMetadataError.FILE_TOO_LARGE
                    call.respondFileError(
                        if (tooLarge) HttpStatusCode.PayloadTooLarge else HttpStatusCode.BadRequest,
                        if (tooLarge) FileProtocolErrorCode.FILE_TOO_LARGE else FileProtocolErrorCode.INVALID_PAYLOAD,
                        wallClockMs,
                        offer.messageId,
                    )
                    return@post
                }
                else -> {
                    call.respondFileError(HttpStatusCode.BadRequest, FileProtocolErrorCode.INVALID_PAYLOAD, wallClockMs, offer.messageId)
                    return@post
                }
            }

            val result = fileCoordinator.create(
                CreateFileTransfersRequest(
                    commandId = FileCommandId(offer.messageId),
                    generationId = authorized.handle.generationId,
                    ownerSessionId = authorized.session.id,
                    files = metadata,
                    batchId = offer.batchId,
                ),
            )
            when (result) {
                FileTransferOperationResult.Accepted -> {
                    val snapshot = fileCoordinator.snapshotFor(
                        authorized.handle.generationId,
                        authorized.session.id,
                    )
                    call.respondJson(
                        HttpStatusCode.OK,
                        FileProtocolJson.encode(
                            FileSnapshotEvent(
                                protocolVersion = FILE_PROTOCOL_VERSION,
                                messageId = offer.messageId,
                                type = FILE_SNAPSHOT_TYPE,
                                timestamp = offer.timestamp,
                                items = snapshot.items.map(FileTransferState::toSnapshotItem),
                            ),
                        ),
                    )
                }
                FileTransferOperationResult.Conflict -> call.respondFileError(
                    HttpStatusCode.Conflict,
                    FileProtocolErrorCode.MESSAGE_CONFLICT,
                    wallClockMs,
                    offer.messageId,
                )
                FileTransferOperationResult.InvalidState,
                FileTransferOperationResult.NotFound,
                -> call.respondFileError(HttpStatusCode.ServiceUnavailable, FileProtocolErrorCode.SESSION_UNAVAILABLE, wallClockMs, offer.messageId)
                is FileTransferOperationResult.Rejected -> call.respondFileError(
                    if (result.failure == FileTransferFailure.CapacityReached) HttpStatusCode.PayloadTooLarge else HttpStatusCode.ServiceUnavailable,
                    if (result.failure == FileTransferFailure.CapacityReached) FileProtocolErrorCode.INVALID_PAYLOAD else FileProtocolErrorCode.SESSION_UNAVAILABLE,
                    wallClockMs,
                    offer.messageId,
                )
            }
        }

        post("/api/v1/files/{transferId}") {
            val authorized = call.authorizeSession(
                coordinator = sessionCoordinator,
                generationHandle = generationHandle,
                allowedHosts = allowedHosts,
            ) ?: return@post
            val transferId = call.parameters["transferId"]
                ?.let { value -> runCatching { FileTransferId(value) }.getOrNull() }
            if (transferId == null) {
                call.respondFileError(HttpStatusCode.NotFound, FileProtocolErrorCode.INVALID_PAYLOAD, wallClockMs)
                return@post
            }
            val item = fileCoordinator.ownedTransfer(
                authorized.handle.generationId,
                authorized.session.id,
                transferId,
            )
            if (item == null) {
                call.respondFileError(HttpStatusCode.NotFound, FileProtocolErrorCode.INVALID_PAYLOAD, wallClockMs)
                return@post
            }
            if (
                item.metadata.direction != FileTransferDirection.BROWSER_TO_ANDROID ||
                item.phase != FileTransferPhase.TRANSFERRING
            ) {
                call.respondFileError(HttpStatusCode.Forbidden, FileProtocolErrorCode.NOT_APPROVED, wallClockMs, transferId.value)
                return@post
            }
            if (call.request.header(HttpHeaders.ContentType) != "application/octet-stream") {
                call.respondFileError(HttpStatusCode.UnsupportedMediaType, FileProtocolErrorCode.INVALID_PAYLOAD, wallClockMs, transferId.value)
                return@post
            }
            val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
            if (contentLength == null || contentLength < 0 || contentLength != item.metadata.sizeBytes) {
                val tooLarge = contentLength != null && contentLength > HARD_MAX_FILE_BYTES
                call.respondFileError(
                    if (tooLarge) HttpStatusCode.PayloadTooLarge else HttpStatusCode.BadRequest,
                    if (tooLarge) FileProtocolErrorCode.FILE_TOO_LARGE else FileProtocolErrorCode.INVALID_PAYLOAD,
                    wallClockMs,
                    transferId.value,
                )
                return@post
            }
            val destination = fileCoordinator.destinationFor(
                authorized.handle.generationId,
                authorized.session.id,
                transferId,
            )
            if (destination == null) {
                call.respondFileError(HttpStatusCode.Forbidden, FileProtocolErrorCode.NOT_APPROVED, wallClockMs, transferId.value)
                return@post
            }
            val factory = uploadTargetFactory
            if (factory == null) {
                call.respondFileError(HttpStatusCode.ServiceUnavailable, FileProtocolErrorCode.STREAM_FAILED, wallClockMs, transferId.value)
                return@post
            }
            val target = runCatching { factory.create(destination, item.metadata) }.getOrNull()
            if (target == null) {
                call.respondFileError(HttpStatusCode.ServiceUnavailable, FileProtocolErrorCode.STREAM_FAILED, wallClockMs, transferId.value)
                return@post
            }
            val managedTarget = ManagedFileUploadTarget(target)
            val uploadJob = checkNotNull(currentCoroutineContext()[Job])
            val attached = fileCoordinator.attachResources(
                transferId,
                UploadRouteResources(uploadJob, managedTarget),
            )
            if (!attached) {
                managedTarget.abort()
                managedTarget.close()
                call.respondFileError(HttpStatusCode.Conflict, FileProtocolErrorCode.STREAM_FAILED, wallClockMs, transferId.value)
                return@post
            }

            val uploadResult = try {
                uploadProcessor.receive(
                    channel = call.receiveChannel(),
                    metadata = item.metadata,
                    declaredContentLength = contentLength,
                    target = managedTarget,
                    onProgress = { bytes ->
                        fileCoordinator.transition(
                            transferId,
                            FileTransferEvent.Progressed(bytes, speedBytesPerSecond = 0),
                        )
                    },
                )
            } finally {
                fileCoordinator.releaseResources(transferId)
            }
            if (uploadResult == RawFileUploadResult.Completed) {
                fileCoordinator.transition(transferId, FileTransferEvent.Verifying)
                fileCoordinator.verify(
                    VerifyFileTransferRequest(
                        transferId = transferId,
                        sizeBytes = item.metadata.sizeBytes,
                        sha256 = item.metadata.sha256,
                    ),
                )
                call.respondOwnedFileSnapshot(fileCoordinator, authorized, transferId.value, wallClockMs)
            } else {
                fileCoordinator.onNetworkFailure(transferId)
                val code = when (uploadResult) {
                    RawFileUploadResult.Oversize -> FileProtocolErrorCode.FILE_TOO_LARGE
                    RawFileUploadResult.ChecksumMismatch -> FileProtocolErrorCode.CHECKSUM_MISMATCH
                    RawFileUploadResult.InvalidContentLength,
                    RawFileUploadResult.PrematureEof,
                    RawFileUploadResult.Failed,
                    -> FileProtocolErrorCode.STREAM_FAILED
                    RawFileUploadResult.Completed -> error("Handled above")
                }
                call.respondFileError(HttpStatusCode.BadRequest, code, wallClockMs, transferId.value)
            }
        }

        post("/api/v1/files/{transferId}/download-grant") {
            val authorized = call.authorizeSession(
                coordinator = sessionCoordinator,
                generationHandle = generationHandle,
                allowedHosts = allowedHosts,
            ) ?: return@post
            if (!call.requireFileJsonRequest(allowedHosts(), wallClockMs)) return@post
            val transferId = call.parameters["transferId"]
                ?.let { value -> runCatching { FileTransferId(value) }.getOrNull() }
            if (transferId == null) {
                call.respondFileError(HttpStatusCode.NotFound, FileProtocolErrorCode.INVALID_PAYLOAD, wallClockMs)
                return@post
            }
            val owned = fileCoordinator.ownedTransfer(
                authorized.handle.generationId,
                authorized.session.id,
                transferId,
            )
            if (owned == null || owned.metadata.direction != FileTransferDirection.ANDROID_TO_BROWSER) {
                call.respondFileError(HttpStatusCode.NotFound, FileProtocolErrorCode.INVALID_PAYLOAD, wallClockMs)
                return@post
            }
            val body = call.receiveBoundedJson(MAX_FILE_CONTROL_JSON_BYTES)
            val request = body?.let {
                runCatching { FileProtocolJson.decode<FileDownloadGrantRequest>(it) }.getOrNull()
            }
            if (request == null || FileProtocolValidator.validate(request) != FileProtocolValidationError.NONE) {
                call.respondFileError(HttpStatusCode.BadRequest, FileProtocolErrorCode.INVALID_PAYLOAD, wallClockMs)
                return@post
            }
            val grant = fileCoordinator.issueDownloadGrant(
                authorized.handle.generationId,
                authorized.session.id,
                transferId,
            )
            if (grant == null) {
                call.respondFileError(HttpStatusCode.Conflict, FileProtocolErrorCode.NOT_APPROVED, wallClockMs, request.messageId)
                return@post
            }
            call.respondJson(
                HttpStatusCode.OK,
                FileProtocolJson.encode(
                    FileDownloadGrantResponse(
                        protocolVersion = FILE_PROTOCOL_VERSION,
                        messageId = request.messageId,
                        type = FILE_DOWNLOAD_GRANT_TYPE,
                        timestamp = wallClockMs(),
                        transferId = transferId.value,
                        downloadPath = grant.downloadPath,
                        expiresAt = grant.expiresAtEpochMillis,
                    ),
                ),
            )
        }

        post("/api/v1/files/{transferId}/verify") {
            val authorized = call.authorizeSession(
                coordinator = sessionCoordinator,
                generationHandle = generationHandle,
                allowedHosts = allowedHosts,
            ) ?: return@post
            if (!call.requireFileJsonRequest(allowedHosts(), wallClockMs)) return@post
            val transferId = call.parameters["transferId"]
                ?.let { value -> runCatching { FileTransferId(value) }.getOrNull() }
            val item = transferId?.let {
                fileCoordinator.ownedTransfer(
                    authorized.handle.generationId,
                    authorized.session.id,
                    it,
                )
            }
            if (transferId == null || item == null) {
                call.respondFileError(HttpStatusCode.NotFound, FileProtocolErrorCode.INVALID_PAYLOAD, wallClockMs)
                return@post
            }
            val body = call.receiveBoundedJson(MAX_FILE_CONTROL_JSON_BYTES)
            val verification = body?.let {
                runCatching { FileProtocolJson.decode<FileVerificationMessage>(it) }.getOrNull()
            }
            if (
                verification == null ||
                FileProtocolValidator.validate(verification) != FileProtocolValidationError.NONE ||
                verification.transferId != transferId.value
            ) {
                call.respondFileError(HttpStatusCode.BadRequest, FileProtocolErrorCode.INVALID_PAYLOAD, wallClockMs)
                return@post
            }
            when (
                fileCoordinator.verify(
                    VerifyFileTransferRequest(
                        transferId = transferId,
                        sizeBytes = verification.sizeBytes,
                        sha256 = verification.sha256,
                    ),
                )
            ) {
                FileTransferOperationResult.Accepted -> call.respondOwnedFileSnapshot(
                    fileCoordinator,
                    authorized,
                    verification.messageId,
                    wallClockMs,
                )
                is FileTransferOperationResult.Rejected -> call.respondFileError(
                    HttpStatusCode.UnprocessableEntity,
                    FileProtocolErrorCode.CHECKSUM_MISMATCH,
                    wallClockMs,
                    verification.messageId,
                )
                FileTransferOperationResult.NotFound -> call.respondFileError(
                    HttpStatusCode.NotFound,
                    FileProtocolErrorCode.INVALID_PAYLOAD,
                    wallClockMs,
                    verification.messageId,
                )
                FileTransferOperationResult.InvalidState,
                FileTransferOperationResult.Conflict,
                -> call.respondFileError(
                    HttpStatusCode.Conflict,
                    FileProtocolErrorCode.MESSAGE_CONFLICT,
                    wallClockMs,
                    verification.messageId,
                )
            }
        }

        delete("/api/v1/transfers/{transferId}") {
            val authorized = call.authorizeSession(
                coordinator = sessionCoordinator,
                generationHandle = generationHandle,
                allowedHosts = allowedHosts,
            ) ?: return@delete
            val transferId = call.parameters["transferId"]
                ?.let { value -> runCatching { FileTransferId(value) }.getOrNull() }
            val item = transferId?.let {
                fileCoordinator.ownedTransfer(
                    authorized.handle.generationId,
                    authorized.session.id,
                    it,
                )
            }
            if (transferId == null || item == null) {
                call.respondFileError(HttpStatusCode.NotFound, FileProtocolErrorCode.INVALID_PAYLOAD, wallClockMs)
                return@delete
            }
            when (fileCoordinator.cancel(transferId)) {
                FileTransferOperationResult.Accepted -> call.respondOwnedFileSnapshot(
                    fileCoordinator,
                    authorized,
                    transferId.value,
                    wallClockMs,
                )
                FileTransferOperationResult.NotFound -> call.respondFileError(
                    HttpStatusCode.NotFound,
                    FileProtocolErrorCode.INVALID_PAYLOAD,
                    wallClockMs,
                )
                else -> call.respondFileError(
                    HttpStatusCode.Conflict,
                    FileProtocolErrorCode.CANCELLED,
                    wallClockMs,
                    transferId.value,
                )
            }
        }

        get("/api/v1/files/{transferId}") {
            val security = SessionRequestSecurityPolicy.validateProtectedHttp(
                host = call.request.header(HttpHeaders.Host),
                origin = call.request.header(HttpHeaders.Origin),
                allowedHosts = allowedHosts(),
                allowMissingOrigin = true,
            )
            if (security is RequestGuardResult.Rejected) {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
                return@get
            }
            val handle = generationHandle()
            val transferId = call.parameters["transferId"]
                ?.let { value -> runCatching { FileTransferId(value) }.getOrNull() }
            val grantToken = call.request.queryParameters["grant"]
                ?.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{22}$")) }
            if (handle == null || transferId == null || grantToken == null) {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
                return@get
            }
            val scope = fileCoordinator.consumeDownloadGrant(
                grantToken,
                handle.generationId,
                transferId,
            )
            if (scope == null) {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
                return@get
            }
            val item = fileCoordinator.ownedTransfer(
                handle.generationId,
                scope.sessionId,
                transferId,
            )
            val factory = downloadSourceFactory
            if (item == null || factory == null) {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
                return@get
            }
            val source = runCatching { factory.create(item.metadata) }.getOrNull()
            if (source == null) {
                fileCoordinator.onNetworkFailure(transferId)
                call.respondText("Unavailable", status = HttpStatusCode.ServiceUnavailable)
                return@get
            }
            fileCoordinator.transition(transferId, FileTransferEvent.Started)
            call.response.header("Referrer-Policy", "no-referrer")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header(HttpHeaders.ContentDisposition, safeAttachmentHeader(item.metadata.displayName, transferId.value))
            try {
                call.respondOutputStream(
                    contentType = runCatching { ContentType.parse(item.metadata.mimeType) }
                        .getOrDefault(ContentType.Application.OctetStream),
                    status = HttpStatusCode.OK,
                    contentLength = item.metadata.sizeBytes,
                ) {
                    val input = source.inputStream()
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total = Math.addExact(total, count.toLong())
                        if (total > item.metadata.sizeBytes) error("Download source exceeded declared size")
                        write(buffer, 0, count)
                        fileCoordinator.transition(
                            transferId,
                            FileTransferEvent.Progressed(total, speedBytesPerSecond = 0),
                        )
                    }
                    if (total != item.metadata.sizeBytes) error("Download source ended before declared size")
                    flush()
                    fileCoordinator.transition(transferId, FileTransferEvent.Verifying)
                }
            } catch (failure: Throwable) {
                fileCoordinator.onNetworkFailure(transferId)
                throw failure
            } finally {
                source.close()
            }
        }
    }
}

private suspend fun ApplicationCall.requireFileJsonRequest(
    allowedHosts: Set<String>,
    wallClockMs: () -> Long,
): Boolean {
    val result = SessionRequestSecurityPolicy.validateJsonApi(
        host = request.header(HttpHeaders.Host),
        origin = request.header(HttpHeaders.Origin),
        contentType = request.header(HttpHeaders.ContentType),
        contentLength = request.header(HttpHeaders.ContentLength)?.toLongOrNull(),
        allowedHosts = allowedHosts,
        maxBodyBytes = MAX_FILE_CONTROL_JSON_BYTES.toLong(),
        bodyTooLargeStatus = HttpStatusCode.PayloadTooLarge,
    )
    if (result is RequestGuardResult.Rejected) {
        if (result.status == HttpStatusCode.PayloadTooLarge) {
            respondFileError(result.status, FileProtocolErrorCode.INVALID_PAYLOAD, wallClockMs)
        } else {
            respondSessionError(
                result.status,
                SessionErrorCode.INVALID_PAYLOAD,
                if (result.status == HttpStatusCode.Forbidden) {
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

private fun safeAttachmentHeader(displayName: String, fallbackId: String): String {
    val normalized = SafeFilenameResolver.normalize(displayName, fallbackId)
    val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.name()).replace("+", "%20")
    val asciiFallback = "devicebridge-" + fallbackId.take(16) + ".bin"
    return "attachment; filename=\"$asciiFallback\"; filename*=UTF-8''$encoded"
}

private suspend fun ApplicationCall.respondFileError(
    status: HttpStatusCode,
    code: FileProtocolErrorCode,
    wallClockMs: () -> Long,
    relatedMessageId: String? = null,
) {
    respondJson(
        status,
        FileProtocolJson.encode(
            FileErrorEvent(
                protocolVersion = FILE_PROTOCOL_VERSION,
                messageId = "server-error",
                type = FILE_ERROR_TYPE,
                timestamp = wallClockMs(),
                relatedMessageId = relatedMessageId?.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{1,64}$")) },
                code = code,
            ),
        ),
    )
}

private suspend fun ApplicationCall.respondOwnedFileSnapshot(
    coordinator: FileTransferCoordinator,
    authorized: AuthorizedSession,
    messageId: String,
    wallClockMs: () -> Long,
) {
    val snapshot = coordinator.snapshotFor(
        authorized.handle.generationId,
        authorized.session.id,
    )
    respondJson(
        HttpStatusCode.OK,
        FileProtocolJson.encode(
            FileSnapshotEvent(
                protocolVersion = FILE_PROTOCOL_VERSION,
                messageId = messageId,
                type = FILE_SNAPSHOT_TYPE,
                timestamp = wallClockMs(),
                items = snapshot.items.map(FileTransferState::toSnapshotItem),
            ),
        ),
    )
}

private class ManagedFileUploadTarget(
    private val delegate: FileUploadTarget,
) : FileUploadTarget {
    private val committed = AtomicBoolean(false)
    private val aborted = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override fun outputStream(): OutputStream = delegate.outputStream()

    override suspend fun commit() {
        if (committed.compareAndSet(false, true)) delegate.commit()
    }

    override suspend fun abort() {
        if (!committed.get() && aborted.compareAndSet(false, true)) delegate.abort()
    }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) delegate.close()
    }
}

private class UploadRouteResources(
    private val job: Job,
    private val target: ManagedFileUploadTarget,
) : FileTransferResources {
    override suspend fun cancelJob() {
        job.cancel()
    }

    override suspend fun closeStreams() {
        target.close()
    }

    override suspend fun cleanupPartial() {
        target.abort()
    }
}

private fun FileMetadataDto.toCandidate() = FileMetadataCandidate(
    transferId = transferId,
    displayName = displayName,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    sha256 = sha256,
    direction = FileTransferDirection.BROWSER_TO_ANDROID,
)

internal fun FileTransferState.toSnapshotItem() = FileSnapshotItem(
    metadata = FileMetadataDto(
        transferId = metadata.id.value,
        displayName = metadata.displayName,
        sizeBytes = metadata.sizeBytes,
        mimeType = metadata.mimeType,
        sha256 = metadata.sha256,
        direction = if (metadata.direction == FileTransferDirection.BROWSER_TO_ANDROID) FileDirectionDto.BROWSER_TO_ANDROID else FileDirectionDto.ANDROID_TO_BROWSER,
    ),
    status = when (phase) {
        FileTransferPhase.QUEUED -> FileTransferStatusDto.QUEUED
        FileTransferPhase.CONNECTING -> FileTransferStatusDto.CONNECTING
        FileTransferPhase.TRANSFERRING -> FileTransferStatusDto.TRANSFERRING
        FileTransferPhase.VERIFYING -> FileTransferStatusDto.VERIFYING
        FileTransferPhase.COMPLETED -> FileTransferStatusDto.COMPLETED
        FileTransferPhase.CANCELLED -> FileTransferStatusDto.CANCELLED
        FileTransferPhase.FAILED -> FileTransferStatusDto.FAILED
    },
    bytesTransferred = bytesTransferred,
    speedBytesPerSecond = speedBytesPerSecond,
)
