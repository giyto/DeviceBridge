package ru.hznik.devicebridge.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import java.net.URI
import java.util.Locale
import ru.hznik.devicebridge.core.protocol.session.MAX_SESSION_JSON_BYTES

sealed interface RequestGuardResult {
    data object Allowed : RequestGuardResult
    data class Rejected(val status: HttpStatusCode) : RequestGuardResult
}

object SessionRequestSecurityPolicy {
    fun validateJsonApi(
        host: String?,
        origin: String?,
        contentType: String?,
        contentLength: Long?,
        allowedHosts: Set<String>,
    ): RequestGuardResult {
        val originResult = validateSameOrigin(host, origin, allowedHosts)
        if (originResult != RequestGuardResult.Allowed) return originResult
        val parsedContentType = contentType
            ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
        if (parsedContentType == null || !parsedContentType.match(ContentType.Application.Json)) {
            return RequestGuardResult.Rejected(HttpStatusCode.BadRequest)
        }
        if (contentLength != null && (contentLength < 0 || contentLength > MAX_SESSION_JSON_BYTES)) {
            return RequestGuardResult.Rejected(HttpStatusCode.BadRequest)
        }
        return RequestGuardResult.Allowed
    }

    fun validateWebSocket(
        host: String?,
        origin: String?,
        allowedHosts: Set<String>,
    ): RequestGuardResult = validateSameOrigin(host, origin, allowedHosts)

    fun validateProtectedHttp(
        host: String?,
        origin: String?,
        allowedHosts: Set<String>,
        allowMissingOrigin: Boolean = false,
    ): RequestGuardResult = if (origin == null && allowMissingOrigin) {
        validateAllowedHost(host, allowedHosts)
    } else {
        validateSameOrigin(host, origin, allowedHosts)
    }

    private fun validateAllowedHost(
        host: String?,
        allowedHosts: Set<String>,
    ): RequestGuardResult {
        val normalizedHost = host?.lowercase(Locale.ROOT)
            ?: return RequestGuardResult.Rejected(HttpStatusCode.Forbidden)
        val normalizedAllowedHosts = allowedHosts.mapTo(mutableSetOf()) {
            it.lowercase(Locale.ROOT)
        }
        return if (normalizedAllowedHosts.isNotEmpty() && normalizedHost in normalizedAllowedHosts) {
            RequestGuardResult.Allowed
        } else {
            RequestGuardResult.Rejected(HttpStatusCode.Forbidden)
        }
    }

    private fun validateSameOrigin(
        host: String?,
        origin: String?,
        allowedHosts: Set<String>,
    ): RequestGuardResult {
        val normalizedHost = host?.lowercase(Locale.ROOT)
            ?: return RequestGuardResult.Rejected(HttpStatusCode.Forbidden)
        val normalizedAllowedHosts = allowedHosts.mapTo(mutableSetOf()) {
            it.lowercase(Locale.ROOT)
        }
        if (normalizedAllowedHosts.isEmpty() || normalizedHost !in normalizedAllowedHosts) {
            return RequestGuardResult.Rejected(HttpStatusCode.Forbidden)
        }
        val originUri = origin
            ?.let { runCatching { URI(it) }.getOrNull() }
            ?: return RequestGuardResult.Rejected(HttpStatusCode.Forbidden)
        val matches = originUri.scheme?.lowercase(Locale.ROOT) == "http" &&
            originUri.rawAuthority?.lowercase(Locale.ROOT) == normalizedHost &&
            originUri.userInfo == null &&
            originUri.rawQuery == null &&
            originUri.rawFragment == null &&
            (originUri.rawPath.isNullOrEmpty() || originUri.rawPath == "/")
        return if (matches) {
            RequestGuardResult.Allowed
        } else {
            RequestGuardResult.Rejected(HttpStatusCode.Forbidden)
        }
    }
}
