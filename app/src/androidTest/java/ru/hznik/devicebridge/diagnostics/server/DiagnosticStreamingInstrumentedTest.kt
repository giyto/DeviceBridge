package ru.hznik.devicebridge.diagnostics.server

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.diagnostics.metrics.AndroidStreamingMemorySampler
import ru.hznik.devicebridge.diagnostics.metrics.StreamingMetricRecord
import ru.hznik.devicebridge.diagnostics.metrics.medianMetricRecords
import ru.hznik.devicebridge.diagnostics.metrics.toMachineReadableLine
import ru.hznik.devicebridge.diagnostics.stream.DEFAULT_DIAGNOSTIC_CHUNK_BYTES
import ru.hznik.devicebridge.diagnostics.stream.DEFAULT_DIAGNOSTIC_PAYLOAD_BYTES
import ru.hznik.devicebridge.diagnostics.stream.DiagnosticPayloadGenerator
import ru.hznik.devicebridge.diagnostics.stream.StreamingSha256

@LargeTest
@RunWith(AndroidJUnit4::class)
class DiagnosticStreamingInstrumentedTest {

    @Test
    fun uploadAndDownloadFiveHundredMegabytesWithinMemoryBudget() = runBlocking {
        val controller = ManagedEmbeddedServerController(
            KtorCioRuntimeFactory(sdkIntProvider = { Build.VERSION.SDK_INT }),
        )
        val port = ServerSocket(0).use { it.localPort }
        controller.start(port)
        val token = (controller.state.value as ServerState.Running).token
        val sampler = AndroidStreamingMemorySampler()
        val runs = requestedMetricRuns()

        try {
            val uploadMetrics = mutableListOf<StreamingMetricRecord>()
            val downloadMetrics = mutableListOf<StreamingMetricRecord>()
            repeat(runs) { index ->
                val upload = sampler.measure("upload", index + 1) {
                    withContext(Dispatchers.IO) {
                        upload(port, token)
                    }
                }.second
                uploadMetrics += upload

                val download = sampler.measure("download", index + 1) {
                    withContext(Dispatchers.IO) {
                        download(port, token)
                    }
                }.second
                downloadMetrics += download
            }

            assertMemoryBudget(uploadMetrics)
            assertMemoryBudget(downloadMetrics)
            emitSummary(uploadMetrics)
            emitSummary(downloadMetrics)
        } finally {
            controller.stop()
        }
    }

    private fun upload(
        port: Int,
        token: String,
    ) {
        val generator = DiagnosticPayloadGenerator(
            totalBytes = DEFAULT_DIAGNOSTIC_PAYLOAD_BYTES,
            chunkSize = DEFAULT_DIAGNOSTIC_CHUNK_BYTES,
        )
        val digest = StreamingSha256()
        val connection = URL("http://127.0.0.1:$port/diagnostics/upload")
            .openConnection() as HttpURLConnection
        connection.run {
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/octet-stream")
            setFixedLengthStreamingMode(DEFAULT_DIAGNOSTIC_PAYLOAD_BYTES)
        }

        try {
            connection.outputStream.use { output ->
                val buffer = ByteArray(DEFAULT_DIAGNOSTIC_CHUNK_BYTES)
                var offset = 0L
                while (offset < generator.totalBytes) {
                    val count = generator.read(offset, buffer)
                    output.write(buffer, 0, count)
                    digest.update(buffer, offset = 0, length = count)
                    offset += count
                }
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            assertEquals(200, connection.responseCode)
            assertTrue(
                body.contains(
                    "\"bytesReceived\":" + DEFAULT_DIAGNOSTIC_PAYLOAD_BYTES,
                ),
            )
            assertTrue(body.contains("\"sha256\":\"" + digest.digestHex() + "\""))
        } finally {
            connection.disconnect()
        }
    }

    private fun download(
        port: Int,
        token: String,
    ) {
        val connection = URL(
            "http://127.0.0.1:$port/diagnostics/download" +
                "?bytes=$DEFAULT_DIAGNOSTIC_PAYLOAD_BYTES",
        ).openConnection() as HttpURLConnection
        connection.run {
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
        }

        try {
            val digest = StreamingSha256()
            val buffer = ByteArray(DEFAULT_DIAGNOSTIC_CHUNK_BYTES)
            connection.inputStream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) {
                        break
                    }
                    digest.update(buffer, offset = 0, length = count)
                }
            }
            assertEquals(200, connection.responseCode)
            assertEquals(DEFAULT_DIAGNOSTIC_PAYLOAD_BYTES, digest.bytesProcessed)
            assertEquals(connection.getHeaderField("X-Content-SHA256"), digest.digestHex())
            assertEquals(
                DEFAULT_DIAGNOSTIC_PAYLOAD_BYTES.toString(),
                connection.getHeaderField("X-Content-Bytes"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun requestedMetricRuns(): Int {
        return InstrumentationRegistry.getArguments()
            .getString(ARG_METRIC_RUNS)
            ?.toIntOrNull()
            ?.coerceIn(1, 3)
            ?: 1
    }

    private fun assertMemoryBudget(records: List<StreamingMetricRecord>) {
        val summary = medianMetricRecords(records)
        val peakDeltaKb = summary.peakPssKb - summary.baselinePssKb
        val retainedDeltaKb = summary.retainedPssKb - summary.baselinePssKb
        assertTrue(
            "Peak PSS delta exceeded budget: $peakDeltaKb KiB",
            peakDeltaKb <= MAX_PEAK_DELTA_KB,
        )
        assertTrue(
            "Retained PSS delta exceeded budget: $retainedDeltaKb KiB",
            retainedDeltaKb <= MAX_RETAINED_DELTA_KB,
        )
    }

    private fun emitSummary(records: List<StreamingMetricRecord>) {
        val line = medianMetricRecords(records).toMachineReadableLine()
        Log.i(LOG_TAG, line)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            STATUS_CODE,
            Bundle().apply { putString(STATUS_KEY, line) },
        )
    }

    private companion object {
        const val ARG_METRIC_RUNS = "deviceBridgeMetricRuns"
        const val LOG_TAG = "DeviceBridgeSpike"
        const val STATUS_KEY = "deviceBridgeStreamingMetricSummary"
        const val STATUS_CODE = 2
        const val NETWORK_TIMEOUT_MILLIS = 10 * 60 * 1_000
        const val MAX_PEAK_DELTA_KB = 128L * 1024L
        const val MAX_RETAINED_DELTA_KB = 32L * 1024L
    }
}
