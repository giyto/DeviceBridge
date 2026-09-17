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
        assertEquals(RouteAccess.PROTECTED, ProductionRoutePolicy.classify("POST", "/api/v1/text"))
        assertEquals(RouteAccess.PROTECTED, ProductionRoutePolicy.classify("POST", "/api/v1/files"))
        assertEquals(RouteAccess.PROTECTED, ProductionRoutePolicy.classify("POST", "/api/v1/files/id"))
        assertEquals(RouteAccess.PROTECTED, ProductionRoutePolicy.classify("POST", "/api/v1/files/id/download-grant"))
        assertEquals(RouteAccess.ONE_TIME_GRANT, ProductionRoutePolicy.classify("GET", "/api/v1/files/id"))
        assertEquals(RouteAccess.PROTECTED, ProductionRoutePolicy.classify("DELETE", "/api/v1/transfers/id"))
        assertEquals(RouteAccess.PROTECTED, ProductionRoutePolicy.classify("POST", "/api/v1/transfers/id/retry"))
    }

    @Test
    fun keepsDiagnosticsHistorySettingsAndTrustedBrowserRoutesUnavailable() {
        val unavailable = listOf(
            "GET" to "/diagnostics/health",
            "POST" to "/api/v1/session/trust",
            "GET" to "/api/v1/history",
            "GET" to "/api/v1/settings",
            "POST" to "/api/v1/files/id/retry",
            "POST" to "/api/v1/files/id/verify",
            "GET" to "/api/v1/files/content://secret",
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
        assertEquals(RouteAccess.NOT_FOUND, ProductionRoutePolicy.classify("GET", "/api/v1/files/a/b"))
        assertEquals(RouteAccess.NOT_FOUND, ProductionRoutePolicy.classify("GET", "/api/v1/files/.."))
    }
}
