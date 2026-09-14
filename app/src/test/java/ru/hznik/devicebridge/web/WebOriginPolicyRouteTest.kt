package ru.hznik.devicebridge.web

import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebOriginPolicyRouteTest {

    @Test
    fun allowedHostWithoutOriginReturnsAssetWithoutCors() = withWebRouteServer { _, request ->
        val response = request("/", emptyMap())

        assertEquals(200, response.statusCode())
        assertFalse(response.headers().firstValue("access-control-allow-origin").isPresent)
    }

    @Test
    fun matchingSameOriginIsAllowedWithoutCors() = withWebRouteServer { port, request ->
        val response = request(
            "/web-manifest.json",
            mapOf("Origin" to "http://127.0.0.1:$port"),
        )

        assertEquals(200, response.statusCode())
        assertFalse(response.headers().firstValue("access-control-allow-origin").isPresent)
    }

    @Test
    fun foreignOriginIsRejectedWithoutAssetContentOrCors() = withWebRouteServer { _, request ->
        val response = request("/", mapOf("Origin" to "https://attacker.example"))
        val body = response.body().toString(StandardCharsets.UTF_8)

        assertEquals(403, response.statusCode())
        assertFalse(body.contains("DeviceBridge"))
        assertFalse(response.headers().firstValue("access-control-allow-origin").isPresent)
    }

    @Test
    fun foreignHostIsRejectedBeforeAssetLookup() = withWebRouteServer { port, _ ->
        val response = rawGet(port = port, hostHeader = "attacker.example")

        assertTrue(response.startsWith("HTTP/1.1 403"))
        assertFalse(response.contains("<title>DeviceBridge</title>"))
        assertFalse(response.contains("Access-Control-Allow-Origin", ignoreCase = true))
    }

    private fun rawGet(port: Int, hostHeader: String): String {
        return Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().bufferedWriter(StandardCharsets.US_ASCII).use { writer ->
                writer.write("GET / HTTP/1.1\r\n")
                writer.write("Host: $hostHeader\r\n")
                writer.write("Connection: close\r\n")
                writer.write("\r\n")
                writer.flush()
                socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
            }
        }
    }
}
