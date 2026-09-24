package ru.hznik.devicebridge.web

import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The same routes as in HTTP mode, reached the way secure mode serves them: through the TLS
 * front door, with a guard that tells TLS connections from plain HTTP ones.
 */
class SecureTransportRouteTest {

    @Before
    fun serveOverTls() {
        SessionRouteTestServer.secureTransportForTests.set(true)
    }

    @After
    fun backToPlainHttp() {
        SessionRouteTestServer.secureTransportForTests.set(false)
    }

    @Test
    fun pairingAndTextWorkOverTls() =
        TextRouteTest().authorizedRequestIsAcceptedOnceAndReturnsStableResponseForRetry()

    @Test
    fun uploadStreamsOverTls() =
        FileUploadRouteTest().approvedOwnerStreamsRawBodyAndCompletesWithChecksum()

    @Test
    fun uploadContinuesFromItsOffsetOverTls() =
        FileUploadRouteTest().uploadOffsetPreparesTheOutputAndTheBodyContinuesFromIt()

    @Test
    fun interruptedDownloadContinuesWithARangeOverTls() =
        FileDownloadRouteTest().interruptedDownloadContinuesWithARangeOfTheSameGrant()

    @Test
    fun httpsPagesMustSendAnHttpsOrigin() = withSessionRouteServer { server ->
        assertTrue(server.secure)
        val paired = server.pairBrowser("Chrome")
        val auth = mapOf("Authorization" to "Bearer ${paired.token}")

        val https = server.request("GET", "/api/v1/status", headers = server.sameOriginJsonHeaders(auth))
        val http = server.request(
            "GET",
            "/api/v1/status",
            headers = auth + ("Origin" to "http://${server.authority}"),
        )

        assertEquals(200, https.statusCode())
        assertEquals(403, http.statusCode())
    }

    @Test
    fun plainHttpOnlyReachesTheSetupSurface() = withSessionRouteServer { server ->
        val certificate = plainGet(server.port, server.authority, ROOT_CERTIFICATE_PATH)
        assertTrue(certificate.head.startsWith("HTTP/1.1 200"))
        assertTrue(certificate.head.contains("application/x-x509-ca-cert"))
        assertEquals(0x30, certificate.body.first().toInt())

        assertTrue(plainGet(server.port, server.authority, "/api/v1/status").head.startsWith("HTTP/1.1 403"))
        assertTrue(plainGet(server.port, server.authority, TLS_PROBE_PATH).head.startsWith("HTTP/1.1 403"))
        val redirect = plainGet(server.port, server.authority, "/somewhere?x=1")
        assertTrue(redirect.head.startsWith("HTTP/1.1 308"))
        assertTrue(redirect.head.contains("Location: https://${server.authority}/somewhere?x=1"))
        assertTrue(plainGet(server.port, "evil.example:80", ROOT_CERTIFICATE_PATH).head.startsWith("HTTP/1.1 403"))
    }

    @Test
    fun trustProbeAnswersOnlyOverTlsAndMayBeReadCrossOrigin() = withSessionRouteServer { server ->
        val probe = server.request("GET", TLS_PROBE_PATH)

        assertEquals(204, probe.statusCode())
        assertEquals("cross-origin", probe.headers().firstValue("Cross-Origin-Resource-Policy").orElse(null))
    }

    @Test
    fun connectionsThatBypassTheFrontDoorAreRefused() = withSessionRouteServer { server ->
        val direct = plainGet(server.backendPort, server.authority, ROOT_CERTIFICATE_PATH)

        assertTrue(direct.head.startsWith("HTTP/1.1 403"))
    }

    private class RawResponse(val head: String, val body: ByteArray)

    private fun plainGet(port: Int, host: String, path: String): RawResponse =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
            val bytes = socket.getInputStream().readBytes()
            val split = (0..bytes.size - 4).first { index ->
                bytes[index] == '\r'.code.toByte() && bytes[index + 1] == '\n'.code.toByte() &&
                    bytes[index + 2] == '\r'.code.toByte() && bytes[index + 3] == '\n'.code.toByte()
            }
            RawResponse(
                head = String(bytes, 0, split, StandardCharsets.ISO_8859_1),
                body = bytes.copyOfRange(split + 4, bytes.size),
            )
        }
}
