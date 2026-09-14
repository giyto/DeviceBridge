package ru.hznik.devicebridge.web

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebStaticRouteTest {

    @Test
    fun rootStreamsIndexWithNoStoreAndSecurityHeaders() = withWebRouteServer { _, request ->
        val response = request("/", emptyMap())

        assertEquals(200, response.statusCode())
        assertEquals("<!doctype html><title>DeviceBridge</title>", response.body().toString(StandardCharsets.UTF_8))
        assertTrue(response.header("content-type").contains("text/html"))
        assertEquals("no-store", response.header("cache-control"))
        assertEquals(
            "default-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
            response.header("content-security-policy"),
        )
        assertEquals("nosniff", response.header("x-content-type-options"))
        assertEquals("no-referrer", response.header("referrer-policy"))
        assertEquals("same-origin", response.header("cross-origin-resource-policy"))
    }

    @Test
    fun hashedAssetStreamsWithImmutableCacheAndExactMetadata() = withWebRouteServer { _, request ->
        val response = request("/assets/index-A1b2C3d4.js", emptyMap())

        assertEquals(200, response.statusCode())
        assertEquals("export{}", response.body().toString(StandardCharsets.UTF_8))
        assertTrue(response.header("content-type").contains("text/javascript"))
        assertEquals(response.body().size.toString(), response.header("content-length"))
        assertEquals("public, max-age=31536000, immutable", response.header("cache-control"))
    }

    @Test
    fun missingAndTraversalPathsDoNotExposeContent() = withWebRouteServer { _, request ->
        val missing = request("/assets/missing.js", emptyMap())
        val traversal = request("/assets/%252e%252e/index.html", emptyMap())

        assertEquals(404, missing.statusCode())
        assertEquals(404, traversal.statusCode())
        assertFalse(missing.body().toString(StandardCharsets.UTF_8).contains("DeviceBridge"))
        assertFalse(traversal.body().toString(StandardCharsets.UTF_8).contains("DeviceBridge"))
    }

    private fun java.net.http.HttpResponse<ByteArray>.header(name: String): String =
        headers().firstValue(name).orElse("")
}
