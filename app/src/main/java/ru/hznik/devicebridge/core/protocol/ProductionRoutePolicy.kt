package ru.hznik.devicebridge.core.protocol

enum class RouteAccess {
    PUBLIC,
    PROTECTED,
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
        else -> RouteAccess.NOT_FOUND
    }
}
