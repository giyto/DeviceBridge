package ru.hznik.devicebridge.web

import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebManifestRouteTest {

    @Test
    fun manifestReturnsExactPublicVersionContractWithoutSecrets() = withWebRouteServer { _, request ->
        val response = request("/web-manifest.json", emptyMap())
        val body = response.body().toString(StandardCharsets.UTF_8)
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals(200, response.statusCode())
        assertTrue(response.header("content-type").contains("application/json"))
        assertEquals("no-store", response.header("cache-control"))
        assertEquals(setOf("protocolVersion", "webAssetVersion"), json.keys)
        assertEquals(1, json.getValue("protocolVersion").jsonPrimitive.int)
        assertEquals(
            "sha256-0123456789abcdef",
            json.getValue("webAssetVersion").jsonPrimitive.content,
        )
        assertFalse(body.contains("token", ignoreCase = true))
        assertFalse(body.contains("device", ignoreCase = true))
        assertFalse(body.contains("sdk", ignoreCase = true))
        assertFalse(body.contains("uptime", ignoreCase = true))
    }

    private fun java.net.http.HttpResponse<ByteArray>.header(name: String): String =
        headers().firstValue(name).orElse("")
}
