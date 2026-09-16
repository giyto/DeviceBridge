package ru.hznik.devicebridge.core.protocol

enum class RouteAccess {
    PUBLIC,
    PROTECTED,
    ONE_TIME_GRANT,
    WEBSOCKET_AUTH_FIRST,
    NOT_FOUND,
}

object ProductionRoutePolicy {
    fun classify(method: String, path: String): RouteAccess = when {
        method == "GET" && path == "/" -> RouteAccess.PUBLIC
        method == "GET" && path == "/web-manifest.json" -> RouteAccess.PUBLIC
        method == "GET" && path.startsWith("/assets/") -> RouteAccess.PUBLIC
        method == "POST" && path == "/api/v1/session/challenge" -> RouteAccess.PUBLIC
        method == "POST" && path == "/api/v1/session/confirm" -> RouteAccess.PUBLIC
        method == "GET" && path == "/api/v1/status" -> RouteAccess.PROTECTED
        method == "DELETE" && path == "/api/v1/session" -> RouteAccess.PROTECTED
        method == "GET" && path == "/api/v1/events" -> RouteAccess.WEBSOCKET_AUTH_FIRST
        method == "POST" && path == "/api/v1/text" -> RouteAccess.PROTECTED
        method == "POST" && path == "/api/v1/files" -> RouteAccess.PROTECTED
        method == "POST" && FILE_ITEM.matches(path) -> RouteAccess.PROTECTED
        method == "POST" && FILE_GRANT.matches(path) -> RouteAccess.PROTECTED
        method == "GET" && FILE_ITEM.matches(path) -> RouteAccess.ONE_TIME_GRANT
        method == "POST" && FILE_VERIFY.matches(path) -> RouteAccess.PROTECTED
        method == "DELETE" && TRANSFER_ITEM.matches(path) -> RouteAccess.PROTECTED
        else -> RouteAccess.NOT_FOUND
    }

    private val ID = "[A-Za-z0-9_-]{1,64}"
    private val FILE_ITEM = Regex("^/api/v1/files/$ID$")
    private val FILE_GRANT = Regex("^/api/v1/files/$ID/download-grant$")
    private val FILE_VERIFY = Regex("^/api/v1/files/$ID/verify$")
    private val TRANSFER_ITEM = Regex("^/api/v1/transfers/$ID$")
}
