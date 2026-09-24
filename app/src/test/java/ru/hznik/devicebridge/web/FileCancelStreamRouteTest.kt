package ru.hznik.devicebridge.web

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.core.protocol.file.FileDownloadGrantResponse
import ru.hznik.devicebridge.core.protocol.file.FileProtocolJson
import ru.hznik.devicebridge.data.file.FileDownloadSource
import ru.hznik.devicebridge.data.file.FileDownloadSourceFactory
import ru.hznik.devicebridge.data.file.FileUploadTarget
import ru.hznik.devicebridge.data.file.FileUploadTargetFactory
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileCommandId
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.session.BrowserSessionId

/**
 * A cancel on either side must stop the bytes, not only the state: a browser that keeps
 * uploading, or a download that keeps arriving, after "Отменено" is what this guards against.
 */
class FileCancelStreamRouteTest {

    @After
    fun plainTransport() {
        SessionRouteTestServer.secureTransportForTests.set(false)
    }

    @Test
    fun phoneCancelCutsARunningUploadOverHttp() = uploadIsCut(secure = false)

    @Test
    fun phoneCancelCutsARunningUploadOverHttps() = uploadIsCut(secure = true)

    @Test
    fun cancelCutsARunningDownloadOverHttp() = downloadIsCut(secure = false)

    @Test
    fun cancelCutsARunningDownloadOverHttps() = downloadIsCut(secure = true)

    private fun uploadIsCut(secure: Boolean) {
        SessionRouteTestServer.secureTransportForTests.set(secure)
        val received = AtomicLong()
        val target = object : FileUploadTarget {
            override fun outputStream(): OutputStream = object : OutputStream() {
                override fun write(value: Int) {
                    received.incrementAndGet()
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    received.addAndGet(len.toLong())
                }
            }
            override suspend fun commit() = Unit
            override suspend fun abort() = Unit
            override suspend fun close() = Unit
        }
        withSessionRouteServer(uploadTargetFactory = FileUploadTargetFactory { _, _ -> target }) { server ->
            val paired = server.pairBrowser("Chrome")
            val offer = server.request(
                "POST",
                "/api/v1/files",
                """{"protocolVersion":1,"messageId":"offer-1","type":"file.offer","timestamp":123,"batchId":"batch-1","items":[{"transferId":"upload-1","displayName":"big.bin","sizeBytes":$BIG,"mimeType":"application/octet-stream","sha256":"${"a".repeat(64)}","direction":"BROWSER_TO_ANDROID"}]}""",
                server.sameOriginJsonHeaders(mapOf("Authorization" to "Bearer ${paired.token}")),
            )
            assertEquals(200, offer.statusCode())
            runBlocking {
                assertEquals(
                    FileTransferOperationResult.Accepted,
                    server.fileCoordinator.approve(FileTransferId("upload-1"), FileDestinationId("tree://downloads")),
                )
            }

            val socket = connect(server)
            val output = socket.getOutputStream()
            output.write(
                (
                    "POST /api/v1/files/upload-1 HTTP/1.1\r\n" +
                        "Host: ${server.authority}\r\n" +
                        "Origin: ${server.scheme}://${server.authority}\r\n" +
                        "Authorization: Bearer ${paired.token}\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Length: $BIG\r\n\r\n"
                    ).encodeToByteArray(),
            )
            val sent = AtomicLong()
            val writer = Thread {
                val chunk = ByteArray(CHUNK)
                try {
                    while (sent.get() < BIG) {
                        output.write(chunk)
                        sent.addAndGet(chunk.size.toLong())
                    }
                } catch (_: IOException) {
                    // The phone closed the connection: what a cancel must do.
                }
            }.apply { start() }

            waitUntil { received.get() >= MIB }
            runBlocking { server.fileCoordinator.cancel(FileTransferId("upload-1")) }
            val sentAtCancel = sent.get()
            writer.join(STOP_WITHIN_MS)
            val stillSending = writer.isAlive
            socket.close()
            writer.join()

            assertTrue("the browser kept uploading after the cancel", !stillSending)
            assertTrue(
                "sent ${sent.get() - sentAtCancel} bytes after the cancel",
                sent.get() - sentAtCancel < BIG / 4,
            )
            assertEquals(
                FileTransferPhase.CANCELLED,
                server.fileCoordinator.state.value.item(FileTransferId("upload-1"))?.phase,
            )
        }
    }

    private fun downloadIsCut(secure: Boolean) {
        SessionRouteTestServer.secureTransportForTests.set(secure)
        val source = object : FileDownloadSource {
            override fun inputStream(): InputStream = object : InputStream() {
                private var left = BIG
                override fun read(): Int = if (left-- > 0) 0 else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left <= 0) return -1
                    val count = minOf(len.toLong(), left).toInt()
                    left -= count
                    return count
                }
            }
            override suspend fun close() = Unit
        }
        withSessionRouteServer(downloadSourceFactory = FileDownloadSourceFactory { source }) { server ->
            val paired = server.pairBrowser("Edge")
            runBlocking {
                server.fileCoordinator.create(
                    CreateFileTransfersRequest(
                        commandId = FileCommandId("android-offer-1"),
                        generationId = server.handle.generationId,
                        ownerSessionId = BrowserSessionId(paired.sessionId),
                        files = listOf(
                            FileTransferMetadata(
                                id = FileTransferId("download-1"),
                                displayName = "big.bin",
                                sizeBytes = BIG,
                                mimeType = "application/octet-stream",
                                sha256 = "a".repeat(64),
                                direction = FileTransferDirection.ANDROID_TO_BROWSER,
                            ),
                        ),
                    ),
                )
            }
            val grant = server.request(
                "POST",
                "/api/v1/files/download-1/download-grant",
                """{"protocolVersion":1,"messageId":"grant-1","type":"file.download_grant.request","timestamp":123}""",
                server.sameOriginJsonHeaders(mapOf("Authorization" to "Bearer ${paired.token}")),
            )
            assertEquals(200, grant.statusCode())
            val path = FileProtocolJson.decode<FileDownloadGrantResponse>(grant.body()).downloadPath

            val socket = connect(server)
            socket.getOutputStream().write(
                "GET $path HTTP/1.1\r\nHost: ${server.authority}\r\n\r\n".encodeToByteArray(),
            )
            val input = socket.getInputStream()
            val read = AtomicLong()
            val reader = Thread {
                val buffer = ByteArray(CHUNK)
                try {
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        read.addAndGet(count.toLong())
                    }
                } catch (_: IOException) {
                    // The phone closed the connection.
                }
            }.apply { start() }

            waitUntil { read.get() >= MIB }
            runBlocking { server.fileCoordinator.cancel(FileTransferId("download-1")) }
            val readAtCancel = read.get()
            reader.join(STOP_WITHIN_MS)
            val stillReceiving = reader.isAlive
            socket.close()
            reader.join()

            assertTrue("the download kept arriving after the cancel", !stillReceiving)
            assertTrue(
                "received ${read.get() - readAtCancel} bytes after the cancel",
                read.get() - readAtCancel < BIG / 4,
            )
        }
    }

    private fun connect(server: SessionRouteTestServer): Socket {
        val plain = Socket("127.0.0.1", server.port)
        if (!server.secure) return plain
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        return context.socketFactory.createSocket(plain, "127.0.0.1", server.port, true)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condition not reached" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val MIB = 1024L * 1024
        const val BIG = 512L * MIB
        const val CHUNK = 64 * 1024
        const val STOP_WITHIN_MS = 5_000L
    }
}
