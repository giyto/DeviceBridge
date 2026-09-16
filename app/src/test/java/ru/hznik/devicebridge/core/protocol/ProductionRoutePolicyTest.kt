package ru.hznik.devicebridge.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductionRoutePolicyTest {

    @Test
    fun exposesOnlyThePublicPairingSurfaceWithoutAuthorization() {
        assertEquals(RouteAccess.PUBLIC, ProductionRoutePolicy.classify("GET", "/"))
        assertEquals(RouteAccess.PUBLIC, ProductionRoutePolicy.classify("GET", "/assets/index.js"))
        assertEquals(RouteAccess.PUBLIC, ProductionRoutePolicy.classify("GET", "/web-manifest.json"))
        assertEquals(RouteAccess.PUBLIC, ProductionRoutePolicy.classify("POST", "/api/v1/session/challenge"))
        assertEquals(RouteAccess.PUBLIC, ProductionRoutePolicy.classify("POST", "/api/v1/session/confirm"))
    }

    @Test
    fun marksCompletedPrivateRoutesAsProtected() {
        assertEquals(RouteAccess.PROTECTED, ProductionRoutePolicy.classify("GET", "/api/v1/status"))
        assertEquals(RouteAccess.PROTECTED, ProductionRoutePolicy.classify("DELETE", "/api/v1/session"))
        assertEquals(RouteAccess.WEBSOCKET_AUTH_FIRST, ProductionRoutePolicy.classify("GET", "/api/v1/events"))
    }

    @Test
    fun keepsDiagnosticsTrustedBrowserAndTransferRoutesUnavailable() {
        val unavailable = listOf(
            "GET" to "/diagnostics/health",
            "POST" to "/api/v1/session/trust",
            "POST" to "/api/v1/text",
            "POST" to "/api/v1/files",
            "GET" to "/api/v1/files/id",
            "DELETE" to "/api/v1/transfers/id",
        )

        unavailable.forEach { (method, path) ->
            assertEquals(RouteAccess.NOT_FOUND, ProductionRoutePolicy.classify(method, path))
        }
    }

    @Test
    fun rejectsWrongMethodsAndLookalikePaths() {
        assertEquals(RouteAccess.NOT_FOUND, ProductionRoutePolicy.classify("GET", "/api/v1/session/challenge"))
        assertEquals(RouteAccess.NOT_FOUND, ProductionRoutePolicy.classify("POST", "/api/v1/status"))
        assertEquals(RouteAccess.NOT_FOUND, ProductionRoutePolicy.classify("GET", "/assets"))
        assertEquals(RouteAccess.NOT_FOUND, ProductionRoutePolicy.classify("GET", "/api/v10/status"))
    }
}
