package ru.hznik.devicebridge.diagnostics.server

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.diagnostics.stream.DEFAULT_DIAGNOSTIC_CHUNK_BYTES
import ru.hznik.devicebridge.diagnostics.stream.DiagnosticPayloadGenerator

@RunWith(AndroidJUnit4::class)
class DiagnosticCancellationInstrumentedTest {

    @Test
    fun uploadAndDownloadCancellationReleaseResources() = runBlocking {
        val controller = ManagedEmbeddedServerController(
            KtorCioRuntimeFactory(sdkIntProvider = { Build.VERSION.SDK_INT }),
        )
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
                chunkSize = DEFAULT_DIAGNOSTIC_CHUNK_BYTES,
            )
            val buffer = ByteArray(DEFAULT_DIAGNOSTIC_CHUNK_BYTES)
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
            socket.soTimeout = 30_000
            socket.getOutputStream().write(
                (
                    "GET /diagnostics/download?bytes=$CANCELLATION_PAYLOAD_BYTES HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\n" +
                        "Authorization: Bearer $token\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            val input = socket.getInputStream()
            val buffer = ByteArray(DEFAULT_DIAGNOSTIC_CHUNK_BYTES)
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

    private suspend fun awaitHealthy(
        port: Int,
        token: String,
    ): Boolean {
        repeat(50) {
            val responseCode = runCatching {
                val connection = URL("http://127.0.0.1:$port/diagnostics/health")
                    .openConnection() as HttpURLConnection
                connection.run {
                    connectTimeout = 2_000
                    readTimeout = 2_000
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $token")
                    try {
                        responseCode
                    } finally {
                        disconnect()
                    }
                }
            }.getOrNull()
            if (responseCode == 200) {
                return true
            }
            delay(100)
        }
        return false
    }

    private companion object {
        const val CANCELLATION_PAYLOAD_BYTES = 64L * 1024L * 1024L
        const val CANCELLATION_PREFIX_BYTES = 1L * 1024L * 1024L
    }
}
