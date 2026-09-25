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
import io.ktor.server.response.respondText
import java.util.Locale

/** While the phone holds its local name, requests to [fromAuthority] (the IP) move to [toAuthority]. */
class AddressRedirect(fromAuthority: String, toAuthority: String) {
    val fromAuthority: String = fromAuthority.lowercase(Locale.ROOT)
    val toAuthority: String = toAuthority.lowercase(Locale.ROOT)
}

/**
 * The phone is reached by one address only. A page opened by IP while the name works is sent to
 * the same page by name; the API is never served by IP then, since only the page itself calls it.
 * Runs before every other check. [schemeFor] is null for a connection another guard rejects.
 */
fun Application.installAddressRedirect(
    redirect: () -> AddressRedirect?,
    schemeFor: (ApplicationCall) -> String?,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        val rule = redirect() ?: return@intercept
        val host = call.request.header(HttpHeaders.Host)?.lowercase(Locale.ROOT) ?: return@intercept
        if (host != rule.fromAuthority) return@intercept
        val scheme = schemeFor(call) ?: return@intercept
        val isRead = call.request.httpMethod == HttpMethod.Get || call.request.httpMethod == HttpMethod.Head
        if (isRead && !call.request.path().startsWith(API_PREFIX)) {
            call.response.header(HttpHeaders.Location, "$scheme://${rule.toAuthority}${call.request.uri}")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.PermanentRedirect)
        } else {
            call.respondText("Forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden)
        }
        finish()
    }
}
