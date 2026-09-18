package ru.hznik.devicebridge.web

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import ru.hznik.devicebridge.core.protocol.file.FileDownloadGrantResponse
import ru.hznik.devicebridge.core.protocol.file.FileProtocolJson
import ru.hznik.devicebridge.data.file.DownloadGrantRegistry
import ru.hznik.devicebridge.data.file.FileDownloadSource
import ru.hznik.devicebridge.data.file.FileDownloadSourceFactory
import ru.hznik.devicebridge.data.file.FileTransferCoordinator
import ru.hznik.devicebridge.data.file.FileUploadTarget
import ru.hznik.devicebridge.data.file.FileUploadTargetFactory
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.data.session.BrowserSessionCoordinator
import ru.hznik.devicebridge.data.session.ChallengeCreationResult
import ru.hznik.devicebridge.data.session.SessionConfirmationResult
import ru.hznik.devicebridge.data.session.security.CryptographicRandom
import ru.hznik.devicebridge.data.session.security.SessionSecretGenerator
import ru.hznik.devicebridge.diagnostics.metrics.AndroidStreamingMemorySampler
import ru.hznik.devicebridge.diagnostics.metrics.StreamingMetricRecord
import ru.hznik.devicebridge.diagnostics.stream.DEFAULT_DIAGNOSTIC_CHUNK_BYTES
import ru.hznik.devicebridge.diagnostics.stream.DEFAULT_DIAGNOSTIC_PAYLOAD_BYTES
import ru.hznik.devicebridge.diagnostics.stream.DiagnosticPayloadGenerator
import ru.hznik.devicebridge.diagnostics.stream.StreamingSha256
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileCommandId
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES
import ru.hznik.devicebridge.domain.session.ServerGenerationId

internal class ProductionFileTransferBenchmarkHarness : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = FixedClock()
    private val sessions = BrowserSessionCoordinator(
        clock = clock,
        secretGenerator = SessionSecretGenerator(DeterministicRandom()),
        scope = scope,
    )
    private val generation = ServerGenerationId(1)
    private val handle = runBlocking { sessions.activate(generation) }
    private val files = FileTransferCoordinator(
        browserSessionState = { sessions.state.value },
        downloadGrantRegistry = DownloadGrantRegistry(nowEpochMillis = { 1_000_000L }),
    ).also { coordinator -> runBlocking { coordinator.activate(generation) } }
    private val uploadTargets = ConcurrentHashMap<FileTransferId, DigestingUploadTarget>()
    private val paired = runBlocking { pairBrowser() }
    private val port = ServerSocket(0).use { it.localPort }
    private val authority = "127.0.0.1:$port"
    private val engine = embeddedServer(
        factory = CIO,
        host = "127.0.0.1",
        port = port,
        module = {
            installFileRoutes(
                sessionCoordinator = sessions,
                fileCoordinator = files,
                generationHandle = { handle },
                allowedHosts = { setOf(authority) },
                wallClockMs = { 1_000_000L },
                uploadTargetFactory = FileUploadTargetFactory { _, metadata ->
                    DigestingUploadTarget().also { uploadTargets[metadata.id] = it }
                },
                downloadSourceFactory = FileDownloadSourceFactory { metadata ->
                    GeneratedDownloadSource(metadata.sizeBytes)
                },
            )
        },
    ).also { it.start(wait = false) }

    suspend fun verifyZeroByteBothDirections() {
        val emptyHash = sha256(ByteArray(0))
        val uploadId = FileTransferId("zero-upload")
        assertEquals(200, offerUpload(uploadId, 0, emptyHash).code)
        assertEquals(
            FileTransferOperationResult.Accepted,
            files.approve(uploadId, FileDestinationId("memory://${uploadId.value}")),
        )
        val uploaded = withContext(Dispatchers.IO) { upload(uploadId, 0, emptyHash) }
        assertEquals(0, uploaded.bytes)
        assertEquals(emptyHash, uploaded.sha256)
        awaitPhase(uploadId, FileTransferPhase.COMPLETED)

        val downloadId = FileTransferId("zero-download")
        createDownload(downloadId, 0, emptyHash)
        val downloaded = withContext(Dispatchers.IO) { download(downloadId, 0, emptyHash) }
        assertEquals(0, downloaded.bytes)
        assertEquals(emptyHash, downloaded.sha256)
        awaitPhase(downloadId, FileTransferPhase.COMPLETED)
    }

    suspend fun verifyOversizeOfferRejected() {
        val transferId = FileTransferId("oversize-offer")
        val response = offerUpload(
            transferId = transferId,
            sizeBytes = HARD_MAX_FILE_BYTES + 1,
            sha256 = "0".repeat(64),
        )
        assertEquals(413, response.code)
        assertFalse(uploadTargets.containsKey(transferId))
        assertEquals(null, files.state.value.item(transferId))
    }

    suspend fun verifyFiveHundredMiBBothDirections() {
        val size = DEFAULT_DIAGNOSTIC_PAYLOAD_BYTES
        val expectedHash = DiagnosticPayloadGenerator(size, CHUNK_BYTES).sha256()
        val sampler = AndroidStreamingMemorySampler()

        val uploadId = FileTransferId("benchmark-upload")
        val (uploadRun, uploadMemory) = sampler.measure("production-file-upload", 1) {
            val (result, ticks) = withUiTicker {
                assertEquals(200, offerUpload(uploadId, size, expectedHash).code)
                assertEquals(
                    FileTransferOperationResult.Accepted,
                    files.approve(uploadId, FileDestinationId("memory://${uploadId.value}")),
                )
                withContext(Dispatchers.IO) { upload(uploadId, size, expectedHash) }
            }
            result.copy(uiTicks = ticks)
        }
        assertTransfer(uploadRun, uploadMemory, expectedHash)
        awaitPhase(uploadId, FileTransferPhase.COMPLETED)

        val downloadId = FileTransferId("benchmark-download")
        val (downloadRun, downloadMemory) = sampler.measure("production-file-download", 1) {
            val (result, ticks) = withUiTicker {
                createDownload(downloadId, size, expectedHash)
                withContext(Dispatchers.IO) { download(downloadId, size, expectedHash) }
            }
            result.copy(uiTicks = ticks)
        }
        assertTransfer(downloadRun, downloadMemory, expectedHash)
        awaitPhase(downloadId, FileTransferPhase.COMPLETED)

        emitResult(uploadRun, uploadMemory)
        emitResult(downloadRun, downloadMemory)
    }

    override fun close() {
        runBlocking { files.close(generation) }
        runBlocking { sessions.closeGeneration(handle) }
        engine.stop(gracePeriodMillis = 0, timeoutMillis = 5_000)
        scope.cancel()
    }

    private suspend fun pairBrowser(): SessionConfirmationResult.Approved = coroutineScope {
        val challenge = sessions.createChallenge(handle, "Chrome", "127.0.0.1")
            as ChallengeCreationResult.Created
        val code = requireNotNull(sessions.state.value.pairingCode).value
        val confirmation = async {
            sessions.confirmAndAwait(
                handle = handle,
                challengeId = challenge.challengeId,
                code = code,
                browserLabel = "Chrome",
                sourceIpv4 = "127.0.0.1",
            )
        }
        while (sessions.state.value.pendingRequests.isEmpty()) delay(10)
        sessions.approve(sessions.state.value.pendingRequests.single().id)
        confirmation.await() as SessionConfirmationResult.Approved
    }

    private fun offerUpload(
        transferId: FileTransferId,
        sizeBytes: Long,
        sha256: String,
    ): HttpResult = postJson(
        path = "/api/v1/files",
        body = """{"protocolVersion":1,"messageId":"offer-${transferId.value}","type":"file.offer","timestamp":123,"batchId":"batch-${transferId.value}","items":[{"transferId":"${transferId.value}","displayName":"${transferId.value}.bin","sizeBytes":$sizeBytes,"mimeType":"application/octet-stream","sha256":"$sha256","direction":"BROWSER_TO_ANDROID"}]}""",
    )

    private suspend fun createDownload(
        transferId: FileTransferId,
        sizeBytes: Long,
        sha256: String,
    ) {
        assertEquals(
            FileTransferOperationResult.Accepted,
            files.create(
                CreateFileTransfersRequest(
                    commandId = FileCommandId("offer-${transferId.value}"),
                    generationId = generation,
                    ownerSessionId = paired.sessionId,
                    files = listOf(
                        FileTransferMetadata(
                            id = transferId,
                            displayName = "${transferId.value}.bin",
                            sizeBytes = sizeBytes,
                            mimeType = "application/octet-stream",
                            sha256 = sha256,
                            direction = FileTransferDirection.ANDROID_TO_BROWSER,
                        ),
                    ),
                ),
            ),
        )
        assertEquals(FileTransferPhase.CONNECTING, files.state.value.item(transferId)?.phase)
    }

    private fun upload(
        transferId: FileTransferId,
        sizeBytes: Long,
        expectedHash: String,
    ): TransferRun {
        val connection = connection("/api/v1/files/${transferId.value}").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Origin", "http://$authority")
            setRequestProperty("Authorization", "Bearer ${paired.token}")
            setRequestProperty("Content-Type", "application/octet-stream")
            setFixedLengthStreamingMode(sizeBytes)
        }
        val generator = DiagnosticPayloadGenerator(sizeBytes, CHUNK_BYTES)
        val digest = StreamingSha256()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            connection.outputStream.use { output ->
                val buffer = ByteArray(CHUNK_BYTES)
                var offset = 0L
                while (offset < sizeBytes) {
                    val count = generator.read(offset, buffer)
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    offset += count
                }
            }
            val response = readResponse(connection)
            assertEquals(response.body, 200, response.code)
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val hash = digest.digestHex()
            assertEquals(expectedHash, hash)
            val target = requireNotNull(uploadTargets[transferId])
            assertTrue(target.committed)
            assertEquals(sizeBytes, target.bytesWritten)
            assertEquals(expectedHash, target.digestHex())
            return TransferRun("browser-to-android", sizeBytes, hash, elapsed)
        } finally {
            connection.disconnect()
        }
    }

    private fun download(
        transferId: FileTransferId,
        sizeBytes: Long,
        expectedHash: String,
    ): TransferRun {
        val grantResponse = postJson(
            path = "/api/v1/files/${transferId.value}/download-grant",
            body = """{"protocolVersion":1,"messageId":"grant-${transferId.value}","type":"file.download_grant.request","timestamp":123}""",
        )
        assertEquals(grantResponse.body, 200, grantResponse.code)
        val grant = FileProtocolJson.decode<FileDownloadGrantResponse>(grantResponse.body)
        assertFalse(grant.downloadPath.contains(paired.token))

        val connection = connection(grant.downloadPath).apply { requestMethod = "GET" }
        val digest = StreamingSha256()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            assertEquals(200, connection.responseCode)
            val buffer = ByteArray(CHUNK_BYTES)
            connection.inputStream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            assertEquals(sizeBytes, digest.bytesProcessed)
            val hash = digest.digestHex()
            assertEquals(expectedHash, hash)
            return TransferRun("android-to-browser", sizeBytes, hash, elapsed)
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(path: String, body: String): HttpResult {
        val bytes = body.encodeToByteArray()
        val connection = connection(path).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Origin", "http://$authority")
            setRequestProperty("Authorization", "Bearer ${paired.token}")
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(bytes.size)
        }
        return try {
            connection.outputStream.use { it.write(bytes) }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun connection(path: String): HttpURLConnection =
        (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            useCaches = false
        }

    private fun readResponse(connection: HttpURLConnection): HttpResult {
        val code = connection.responseCode
        val stream = if (code >= 400) connection.errorStream else connection.inputStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return HttpResult(code, body)
    }

    private suspend fun <T> withUiTicker(block: suspend () -> T): Pair<T, Int> = coroutineScope {
        val ticks = AtomicInteger()
        val ticker = launch(Dispatchers.Main) {
            while (isActive) {
                ticks.incrementAndGet()
                delay(50)
            }
        }
        val result = try {
            block()
        } finally {
            ticker.cancelAndJoin()
        }
        assertTrue("Android main thread did not remain responsive", ticks.get() >= 2)
        result to ticks.get()
    }

    private suspend fun awaitPhase(
        transferId: FileTransferId,
        expected: FileTransferPhase,
    ) {
        withTimeout(5_000) {
            while (files.state.value.item(transferId)?.phase != expected) {
                delay(10)
            }
        }
    }

    private fun assertTransfer(
        run: TransferRun,
        memory: StreamingMetricRecord,
        expectedHash: String,
    ) {
        assertEquals(DEFAULT_DIAGNOSTIC_PAYLOAD_BYTES, run.bytes)
        assertEquals(expectedHash, run.sha256)
        assertTrue("Transfer elapsed time was not recorded", run.elapsedMs > 0)
        assertTrue("UI ticker did not advance", run.uiTicks >= 2)
        assertTrue(
            "Peak PSS delta exceeded budget",
            memory.peakPssKb - memory.baselinePssKb <= MAX_PEAK_DELTA_KB,
        )
        assertTrue(
            "Retained PSS delta exceeded budget",
            memory.retainedPssKb - memory.baselinePssKb <= MAX_RETAINED_DELTA_KB,
        )
    }

    private fun emitResult(run: TransferRun, memory: StreamingMetricRecord) {
        val line = "DEVICEBRIDGE_FILE_TRANSFER_METRIC " +
            "sdk=${Build.VERSION.SDK_INT} direction=${run.direction} bytes=${run.bytes} " +
            "sha256=${run.sha256} elapsedMs=${run.elapsedMs} uiTicks=${run.uiTicks} " +
            "baselinePssKb=${memory.baselinePssKb} peakPssKb=${memory.peakPssKb} " +
            "retainedPssKb=${memory.retainedPssKb} javaHeapPeakBytes=${memory.javaHeapPeakBytes}"
        Log.i(LOG_TAG, line)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            STATUS_CODE,
            Bundle().apply { putString(STATUS_KEY, line) },
        )
    }

    private class DigestingUploadTarget : FileUploadTarget {
        private val digest = MessageDigest.getInstance("SHA-256")
        var bytesWritten: Long = 0
            private set
        var committed: Boolean = false
            private set
        private var finalHash: String? = null

        override fun outputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) {
                val byte = value.toByte()
                digest.update(byte)
                bytesWritten += 1
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                digest.update(bytes, offset, length)
                bytesWritten += length
            }
        }

        override suspend fun commit() {
            committed = true
        }

        override suspend fun abort() = Unit
        override suspend fun close() = Unit

        fun digestHex(): String = finalHash ?: digest.digest()
            .joinToString("") { "%02x".format(it) }
            .also { finalHash = it }
    }

    private class GeneratedDownloadSource(sizeBytes: Long) : FileDownloadSource {
        private val input = GeneratedInputStream(sizeBytes)
        override fun inputStream(): InputStream = input
        override suspend fun close() = input.close()
    }

    private class GeneratedInputStream(
        private val sizeBytes: Long,
    ) : InputStream() {
        private val generator = DiagnosticPayloadGenerator(sizeBytes, CHUNK_BYTES)
        private var position = 0L

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (position >= sizeBytes) return -1
            if (length == 0) return 0
            val count = minOf(length.toLong(), sizeBytes - position).toInt()
            val buffer = if (offset == 0 && count == target.size) target else ByteArray(count)
            val generated = generator.read(position, buffer)
            if (buffer !== target) buffer.copyInto(target, offset, 0, generated)
            position += generated
            return generated
        }
    }

    private class FixedClock : MonotonicClock {
        override fun nowMs(): Long = 1_000L
    }

    private class DeterministicRandom : CryptographicRandom {
        private var seed = 1
        override fun nextInt(bound: Int): Int = 123_456 % bound
        override fun nextBytes(size: Int): ByteArray = ByteArray(size) { (seed++).toByte() }
    }

    private data class HttpResult(val code: Int, val body: String)
    private data class TransferRun(
        val direction: String,
        val bytes: Long,
        val sha256: String,
        val elapsedMs: Long,
        val uiTicks: Int = 0,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val CHUNK_BYTES = DEFAULT_DIAGNOSTIC_CHUNK_BYTES
        const val NETWORK_TIMEOUT_MILLIS = 10 * 60 * 1_000
        const val MAX_PEAK_DELTA_KB = 128L * 1024L
        const val MAX_RETAINED_DELTA_KB = 32L * 1024L
        const val LOG_TAG = "DeviceBridgeFileTest"
        const val STATUS_KEY = "deviceBridgeFileTransferMetric"
        const val STATUS_CODE = 2
    }
}
