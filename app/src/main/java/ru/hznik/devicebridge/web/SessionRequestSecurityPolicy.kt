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
        maxBodyBytes: Long = MAX_SESSION_JSON_BYTES.toLong(),
        bodyTooLargeStatus: HttpStatusCode = HttpStatusCode.BadRequest,
        originScheme: String = DEFAULT_ORIGIN_SCHEME,
    ): RequestGuardResult {
        require(maxBodyBytes > 0)
        val originResult = validateSameOrigin(host, origin, allowedHosts, originScheme)
        if (originResult != RequestGuardResult.Allowed) return originResult
        val parsedContentType = contentType
            ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
        if (parsedContentType == null || !parsedContentType.match(ContentType.Application.Json)) {
            return RequestGuardResult.Rejected(HttpStatusCode.BadRequest)
        }
        if (contentLength != null && (contentLength < 0 || contentLength > maxBodyBytes)) {
            return RequestGuardResult.Rejected(bodyTooLargeStatus)
        }
        return RequestGuardResult.Allowed
    }

    fun validateWebSocket(
        host: String?,
        origin: String?,
        allowedHosts: Set<String>,
        originScheme: String = DEFAULT_ORIGIN_SCHEME,
    ): RequestGuardResult = validateSameOrigin(host, origin, allowedHosts, originScheme)

    fun validateProtectedHttp(
        host: String?,
        origin: String?,
        allowedHosts: Set<String>,
        allowMissingOrigin: Boolean = false,
        originScheme: String = DEFAULT_ORIGIN_SCHEME,
    ): RequestGuardResult = if (origin == null && allowMissingOrigin) {
        validateAllowedHost(host, allowedHosts)
    } else {
        validateSameOrigin(host, origin, allowedHosts, originScheme)
    }

    private fun validateAllowedHost(
        host: String?,
        allowedHosts: Set<String>,
    ): RequestGuardResult = if (allowedHostOrNull(host, allowedHosts) != null) {
        RequestGuardResult.Allowed
    } else {
        RequestGuardResult.Rejected(HttpStatusCode.Forbidden)
    }

    /** The lowercased [host] when it is one of [allowedHosts], otherwise null. */
    private fun allowedHostOrNull(host: String?, allowedHosts: Set<String>): String? {
        val normalizedHost = host?.lowercase(Locale.ROOT) ?: return null
        val normalizedAllowedHosts = allowedHosts.mapTo(mutableSetOf()) {
            it.lowercase(Locale.ROOT)
        }
        return normalizedHost.takeIf {
            normalizedAllowedHosts.isNotEmpty() && it in normalizedAllowedHosts
        }
    }

    private fun validateSameOrigin(
        host: String?,
        origin: String?,
        allowedHosts: Set<String>,
        originScheme: String,
    ): RequestGuardResult {
        val normalizedHost = allowedHostOrNull(host, allowedHosts)
            ?: return RequestGuardResult.Rejected(HttpStatusCode.Forbidden)
        val originUri = origin
            ?.let { runCatching { URI(it) }.getOrNull() }
            ?: return RequestGuardResult.Rejected(HttpStatusCode.Forbidden)
        val matches = originUri.scheme?.lowercase(Locale.ROOT) == originScheme &&
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
