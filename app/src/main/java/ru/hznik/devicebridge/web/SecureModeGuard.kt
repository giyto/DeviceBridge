package ru.hznik.devicebridge.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import java.util.Locale
import ru.hznik.devicebridge.data.tls.FileProviderRootCertificateExporter
import ru.hznik.devicebridge.data.tls.RelayedPeer

internal const val DEFAULT_ORIGIN_SCHEME = "http"

private val OriginSchemeKey = AttributeKey<String>("DeviceBridgeOriginScheme")

/** The scheme a same-origin request from this connection must carry in its `Origin`. */
fun ApplicationCall.originScheme(): String =
    attributes.getOrNull(OriginSchemeKey) ?: DEFAULT_ORIGIN_SCHEME

/**
 * In secure mode every connection arrives through the TLS front door. This guard runs before
 * any route: it drops connections the front door did not relay, lets TLS connections through
 * as `https`, and gives plain HTTP connections only the certificate setup page, the root
 * certificate and the page's assets.
 */
fun Application.installSecureModeGuard(
    peerFor: (remotePort: Int) -> RelayedPeer?,
    rootCertificate: () -> ByteArray,
    webAssetProvider: WebAssetProvider,
    allowedHosts: () -> Set<String>,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        val peer = peerFor(call.request.local.remotePort)
        val host = call.request.header(HttpHeaders.Host)?.lowercase(Locale.ROOT)
        val hosts = allowedHosts().mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        if (peer == null || host == null || host !in hosts) {
            call.respondText("Forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            finish()
            return@intercept
        }
        call.attributes.put(OriginSchemeKey, peer.scheme)
        val path = call.request.path()
        val isRead = call.request.httpMethod == HttpMethod.Get || call.request.httpMethod == HttpMethod.Head
        when {
            isRead && path == ROOT_CERTIFICATE_PATH -> {
                call.respondRootCertificate(rootCertificate())
                finish()
            }
            peer.secure && isRead && path == TLS_PROBE_PATH -> {
                // Read cross-origin by the setup page with `no-cors`, so it must not be same-origin only.
                call.response.header("Cross-Origin-Resource-Policy", "cross-origin")
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(HttpStatusCode.NoContent)
                finish()
            }
            peer.secure -> Unit
            isRead && path.startsWith(ASSETS_PREFIX) -> Unit
            isRead && (path == "/" || path == SETUP_PAGE_PATH) -> {
                call.respondSetupPage(webAssetProvider, httpsOrigin = "https://$host")
                finish()
            }
            isRead && !path.startsWith(API_PREFIX) -> {
                call.response.header(HttpHeaders.Location, "https://$host${call.request.uri}")
                call.respond(HttpStatusCode.PermanentRedirect)
                finish()
            }
            else -> {
                call.respondText("Forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                finish()
            }
        }
    }
}

private suspend fun ApplicationCall.respondRootCertificate(der: ByteArray) {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"${FileProviderRootCertificateExporter.FILE_NAME}\"",
    )
    response.header("X-Content-Type-Options", "nosniff")
    respondBytes(der, ContentType.parse(FileProviderRootCertificateExporter.MIME_TYPE), HttpStatusCode.OK)
}

private suspend fun ApplicationCall.respondSetupPage(
    webAssetProvider: WebAssetProvider,
    httpsOrigin: String,
) {
    val asset = webAssetProvider.find(SETUP_PAGE_PATH)
    if (asset == null) {
        respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
        return
    }
    response.header("Content-Security-Policy", setupPageContentSecurityPolicy(httpsOrigin))
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header(HttpHeaders.CacheControl, "no-store")
    respondAsset(asset)
}

/** Like the app's policy, plus the HTTPS origin the page probes to see whether the root is trusted. */
internal fun setupPageContentSecurityPolicy(httpsOrigin: String): String =
    "default-src 'self'; script-src 'self'; connect-src 'self' $httpsOrigin; " +
        "object-src 'none'; base-uri 'none'; frame-ancestors 'none'"

const val ROOT_CERTIFICATE_PATH = "/devicebridge-ca.crt"
const val TLS_PROBE_PATH = "/api/v1/tls-probe"
const val SETUP_PAGE_PATH = "/setup.html"
private const val ASSETS_PREFIX = "/assets/"
internal const val API_PREFIX = "/api/"
