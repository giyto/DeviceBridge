package ru.hznik.devicebridge.web

import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.hznik.devicebridge.data.file.FileUploadTarget
import ru.hznik.devicebridge.data.file.FileUploadTargetFactory
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferPhase

/** The coordinator's final check sees what was received, not what the browser declared. */
class FileUploadVerificationTest {

    @Test
    fun processorReportsWhatItMeasuredOverTheWholeFile() = runTest {
        val file = "hello resumable world".encodeToByteArray()
        val stored = 6
        val remaining = file.copyOfRange(stored, file.size)
        val metadata = metadata(file.size.toLong(), sha256(file))
        suspend fun receive(prefix: ByteArray): Pair<RawFileUploadResult, ReceivedFileDigest?> {
            var received: ReceivedFileDigest? = null
            val result = RawFileUploadProcessor(bufferSize = 4).receive(
                channel = ByteReadChannel(remaining),
                metadata = metadata,
                declaredContentLength = remaining.size.toLong(),
                target = MemoryTarget(prefix),
                onReceived = { received = it },
            )
            return result to received
        }

        val resumed = receive(file.copyOf(stored))
        val damagedPrefix = file.copyOf(stored).also { it[0] = 'j'.code.toByte() }
        val damaged = receive(damagedPrefix)

        assertEquals(RawFileUploadResult.Completed, resumed.first)
        assertEquals(ReceivedFileDigest(file.size.toLong(), sha256(file)), resumed.second)
        assertEquals(RawFileUploadResult.ChecksumMismatch, damaged.first)
        assertEquals(
            ReceivedFileDigest(file.size.toLong(), sha256(damagedPrefix + remaining)),
            damaged.second,
        )
    }

    @Test
    fun routeVerifiesTheUploadByTheMeasuredDigestRatherThanTheOffer() {
        val payload = "hello".encodeToByteArray()
        val misreporting = MisreportingProcessor(
            ReceivedFileDigest(payload.size.toLong(), sha256("other".encodeToByteArray())),
        )
        withSessionRouteServer(
            uploadTargetFactory = FileUploadTargetFactory { _, _ -> MemoryTarget() },
            uploadProcessor = misreporting,
        ) { server ->
            val paired = server.pairBrowser("Chrome")
            val offer = server.request(
                "POST",
                "/api/v1/files",
                """{"protocolVersion":1,"messageId":"offer-1","type":"file.offer","timestamp":123,"batchId":"batch-1","items":[{"transferId":"upload-1","displayName":"safe.bin","sizeBytes":${payload.size},"mimeType":"application/octet-stream","sha256":"${sha256(payload)}","direction":"BROWSER_TO_ANDROID"}]}""",
                server.sameOriginJsonHeaders(mapOf("Authorization" to "Bearer ${paired.token}")),
            )
            assertEquals(200, offer.statusCode())
            runBlocking {
                assertEquals(
                    FileTransferOperationResult.Accepted,
                    server.fileCoordinator.approve(
                        FileTransferId("upload-1"),
                        FileDestinationId("tree://downloads"),
                    ),
                )
            }

            server.requestBytes(
                "POST",
                "/api/v1/files/upload-1",
                payload,
                server.sameOriginBinaryHeaders(mapOf("Authorization" to "Bearer ${paired.token}")),
            )

            val item = server.fileCoordinator.state.value.item(FileTransferId("upload-1"))
            assertEquals(1, misreporting.calls)
            assertEquals(FileTransferPhase.FAILED, item?.phase)
            assertEquals(FileTransferFailure.ChecksumMismatch, item?.failure)
        }
    }

    /** Receives the body for real but reports a different measurement to the route. */
    private class MisreportingProcessor(
        private val reported: ReceivedFileDigest,
    ) : RawFileUploadProcessor() {
        var calls = 0

        override suspend fun receive(
            channel: ByteReadChannel,
            metadata: FileTransferMetadata,
            declaredContentLength: Long?,
            target: FileUploadTarget,
            onProgress: suspend (Long) -> Unit,
            onReceived: (ReceivedFileDigest) -> Unit,
        ): RawFileUploadResult {
            calls += 1
            return super.receive(channel, metadata, declaredContentLength, target, onProgress) {
                onReceived(reported)
            }
        }
    }

    private class MemoryTarget(prefix: ByteArray = ByteArray(0)) : FileUploadTarget {
        private val prefixDigest = MessageDigest.getInstance("SHA-256").also { it.update(prefix) }
        private val output = ByteArrayOutputStream()
        override val offsetBytes: Long = prefix.size.toLong()
        override fun digest(): MessageDigest = prefixDigest
        override fun outputStream() = output
        override suspend fun commit() = Unit
        override suspend fun abort() = Unit
        override suspend fun close() = Unit
    }

    private fun metadata(size: Long, hash: String) = FileTransferMetadata(
        id = FileTransferId("upload-1"),
        displayName = "safe.bin",
        sizeBytes = size,
        mimeType = "application/octet-stream",
        sha256 = hash,
        direction = FileTransferDirection.BROWSER_TO_ANDROID,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
