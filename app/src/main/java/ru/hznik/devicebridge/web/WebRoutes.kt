package ru.hznik.devicebridge.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.installWebRoutes(
    webAssetProvider: WebAssetProvider,
    allowedHosts: () -> Set<String>,
) {
    routing {
        get("/") {
            call.respondWebAsset(
                requestPath = "/",
                webAssetProvider = webAssetProvider,
                allowedHosts = allowedHosts,
                cacheControl = NO_STORE,
            )
        }

        get("/web-manifest.json") {
            call.respondWebAsset(
                requestPath = "/web-manifest.json",
                webAssetProvider = webAssetProvider,
                allowedHosts = allowedHosts,
                cacheControl = NO_STORE,
            )
        }

        get("/assets/{path...}") {
            call.respondWebAsset(
                requestPath = call.request.path(),
                webAssetProvider = webAssetProvider,
                allowedHosts = allowedHosts,
                cacheControl = IMMUTABLE_ASSET_CACHE,
            )
        }
    }
}

private suspend fun ApplicationCall.respondWebAsset(
    requestPath: String,
    webAssetProvider: WebAssetProvider,
    allowedHosts: () -> Set<String>,
    cacheControl: String,
) {
    addWebSecurityHeaders()
    val security = SessionRequestSecurityPolicy.validateProtectedHttp(
        host = request.header(HttpHeaders.Host),
        origin = request.header(HttpHeaders.Origin),
        allowedHosts = allowedHosts(),
        allowMissingOrigin = true,
        originScheme = originScheme(),
    )
    if (security is RequestGuardResult.Rejected) {
        respondText(
            text = "Forbidden",
            contentType = ContentType.Text.Plain,
            status = HttpStatusCode.Forbidden,
        )
        return
    }

    val asset = webAssetProvider.find(requestPath)
    if (asset == null) {
        respondText(
            text = "Not found",
            contentType = ContentType.Text.Plain,
            status = HttpStatusCode.NotFound,
        )
        return
    }

    response.header(HttpHeaders.CacheControl, cacheControl)
    respondAsset(asset)
}

/** Streams a bundled web asset with its content type and length. */
internal suspend fun ApplicationCall.respondAsset(asset: WebAssetResource) {
    respondOutputStream(
        contentType = ContentType.parse(asset.contentType),
        status = HttpStatusCode.OK,
        contentLength = asset.length,
    ) {
        asset.openStream().use { input -> input.copyTo(this) }
    }
}

private fun ApplicationCall.addWebSecurityHeaders() {
    response.header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("Cross-Origin-Resource-Policy", "same-origin")
}

private const val NO_STORE = "no-store"
private const val IMMUTABLE_ASSET_CACHE = "public, max-age=31536000, immutable"
private const val CONTENT_SECURITY_POLICY =
    "default-src 'self'; " +
        "script-src 'self' 'sha256-F5EhQ4Xw10HZb4yMlvyeV6RnFM6rJV0SIN4Hvm5VCkc='; " +
        "connect-src 'self'; object-src 'none'; " +
        "base-uri 'none'; frame-ancestors 'none'"
