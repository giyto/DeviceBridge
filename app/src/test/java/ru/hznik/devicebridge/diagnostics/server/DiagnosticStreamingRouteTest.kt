package ru.hznik.devicebridge.diagnostics.server

import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.diagnostics.stream.DiagnosticPayloadGenerator
import ru.hznik.devicebridge.diagnostics.stream.StreamingSha256

class DiagnosticStreamingRouteTest {

    @Test
    fun uploadStreamsBytesAndReturnsSha256() = runBlocking {
        val controller = ManagedEmbeddedServerController(KtorCioRuntimeFactory())
        val port = ServerSocket(0).use { it.localPort }
        controller.start(port)
        val token = (controller.state.value as ServerState.Running).token
        val generator = DiagnosticPayloadGenerator(
            totalBytes = TEST_PAYLOAD_BYTES,
            chunkSize = TEST_CHUNK_BYTES,
        )

        try {
            val request = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:$port/diagnostics/upload"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/octet-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofInputStream {
                        GeneratorInputStream(generator)
                    },
                )
                .build()

            val response = HttpClient.newHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(200, response.statusCode())
            assertTrue(
                response.body().contains(
                    "\"bytesReceived\":" + TEST_PAYLOAD_BYTES,
                ),
            )
            assertTrue(
                response.body().contains(
                    "\"sha256\":\"" + generator.sha256() + "\"",
                ),
            )
        } finally {
            controller.stop()
        }
    }

    @Test
    fun downloadStreamsExactBytesAndSha256() = runBlocking {
        val controller = ManagedEmbeddedServerController(KtorCioRuntimeFactory())
        val port = ServerSocket(0).use { it.localPort }
        controller.start(port)
        val token = (controller.state.value as ServerState.Running).token
        val expected = DiagnosticPayloadGenerator(
            totalBytes = TEST_PAYLOAD_BYTES,
            chunkSize = TEST_CHUNK_BYTES,
        )

        try {
            val request = HttpRequest.newBuilder()
                .uri(
                    URI(
                        "http://127.0.0.1:$port/diagnostics/download" +
                            "?bytes=$TEST_PAYLOAD_BYTES",
                    ),
                )
                .header("Authorization", "Bearer $token")
                .GET()
                .build()
            val response = HttpClient.newHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            val digest = StreamingSha256()
            val buffer = ByteArray(TEST_CHUNK_BYTES)
            response.body().use { stream ->
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) {
                        break
                    }
                    digest.update(buffer, offset = 0, length = count)
                }
            }

            assertEquals(200, response.statusCode())
            assertEquals(TEST_PAYLOAD_BYTES, digest.bytesProcessed)
            assertEquals(expected.sha256(), digest.digestHex())
            assertEquals(
                TEST_PAYLOAD_BYTES.toString(),
                response.headers().firstValue("X-Content-Bytes").orElse(null),
            )
            assertEquals(
                expected.sha256(),
                response.headers().firstValue("X-Content-SHA256").orElse(null),
            )
        } finally {
            controller.stop()
        }
    }

    @Test
    fun cancelledUploadAndDownloadKeepHealthAvailable() = runBlocking {
        val controller = ManagedEmbeddedServerController(KtorCioRuntimeFactory())
        val port = ServerSocket(0).use { it.localPort }
        controller.start(port)
        val token = (controller.state.value as ServerState.Running).token

        try {
            abortUpload(port, token)
            assertTrue(awaitHealthy(port, token))

            abortDownload(port, token)
            assertTrue(awaitHealthy(port, token))
        } finally {
            controller.stop()
        }
    }

    private fun abortUpload(
        port: Int,
        token: String,
    ) {
        Socket("127.0.0.1", port).use { socket ->
            val output = socket.getOutputStream()
            output.write(
                (
                    "POST /diagnostics/upload HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\n" +
                        "Authorization: Bearer $token\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Length: $CANCELLATION_PAYLOAD_BYTES\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            val generator = DiagnosticPayloadGenerator(
                totalBytes = CANCELLATION_PREFIX_BYTES,
                chunkSize = TEST_CHUNK_BYTES,
            )
            val buffer = ByteArray(TEST_CHUNK_BYTES)
            var offset = 0L
            while (offset < generator.totalBytes) {
                val count = generator.read(offset, buffer)
                output.write(buffer, 0, count)
                offset += count
            }
            output.flush()
        }
    }

    private fun abortDownload(
        port: Int,
        token: String,
    ) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write(
                (
                    "GET /diagnostics/download?bytes=$CANCELLATION_PAYLOAD_BYTES HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\n" +
                        "Authorization: Bearer $token\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            val input = socket.getInputStream()
            val buffer = ByteArray(TEST_CHUNK_BYTES)
            var received = 0L
            while (received < CANCELLATION_PREFIX_BYTES) {
                val count = input.read(buffer)
                if (count < 0) {
                    break
                }
                received += count
            }
            assertTrue(received >= CANCELLATION_PREFIX_BYTES)
        }
    }

    private fun awaitHealthy(
        port: Int,
        token: String,
    ): Boolean {
        repeat(50) {
            val request = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:$port/diagnostics/health"))
                .header("Authorization", "Bearer $token")
                .GET()
                .build()
            val responseCode = runCatching {
                HttpClient.newHttpClient().send(
                    request,
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode()
            }.getOrNull()
            if (responseCode == 200) {
                return true
            }
            Thread.sleep(100)
        }
        return false
    }

    private class GeneratorInputStream(
        private val generator: DiagnosticPayloadGenerator,
    ) : InputStream() {
        private var offset = 0L
        private val singleByte = ByteArray(1)

        override fun read(): Int {
            val count = read(singleByte, 0, 1)
            return if (count < 0) -1 else singleByte[0].toInt() and 0xFF
        }

        override fun read(
            target: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (offset >= generator.totalBytes) {
                return -1
            }
            val temporary = if (off == 0 && len == target.size) {
                target
            } else {
                ByteArray(len)
            }
            val count = generator.read(offset, temporary)
            if (temporary !== target) {
                temporary.copyInto(target, destinationOffset = off, endIndex = count)
            }
            offset += count
            return count
        }
    }

    private companion object {
        const val TEST_PAYLOAD_BYTES = 2L * 1024L * 1024L + 17L
        const val TEST_CHUNK_BYTES = 64 * 1024
        const val CANCELLATION_PAYLOAD_BYTES = 64L * 1024L * 1024L
        const val CANCELLATION_PREFIX_BYTES = 1L * 1024L * 1024L
    }
}
