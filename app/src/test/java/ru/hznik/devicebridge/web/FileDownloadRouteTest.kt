package ru.hznik.devicebridge.web

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.core.protocol.file.FileDownloadGrantResponse
import ru.hznik.devicebridge.core.protocol.file.FileProtocolJson
import ru.hznik.devicebridge.data.file.FileDownloadSource
import ru.hznik.devicebridge.data.file.FileDownloadSourceFactory
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileCommandId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.session.BrowserSessionId

class FileDownloadRouteTest {

    @Test
    fun completedNativeDownloadPromotesNextQueuedFileWithoutVerification() {
        val payload = "download-payload".encodeToByteArray()
        val source = RecordingSource(payload)
        withSessionRouteServer(
            downloadSourceFactory = FileDownloadSourceFactory { source },
        ) { server ->
            val paired = server.pairBrowser("Edge")
            createDownloadOffers(
                server,
                paired.sessionId,
                payload,
                "download-1" to "report.txt",
                "download-2" to "next.txt",
            )

            val grantResponse = server.request(
                "POST",
                "/api/v1/files/download-1/download-grant",
                grantBody(),
                server.sameOriginJsonHeaders(mapOf("Authorization" to "Bearer ${paired.token}")),
            )
            assertEquals(200, grantResponse.statusCode())
            val grant = FileProtocolJson.decode<FileDownloadGrantResponse>(grantResponse.body())
            assertFalse(grant.downloadPath.contains(paired.token))

            val downloaded = server.request("GET", grant.downloadPath)
            val replay = server.request("GET", grant.downloadPath)

            assertEquals(200, downloaded.statusCode())
            assertEquals("download-payload", downloaded.body())
            assertEquals("no-referrer", downloaded.headers().firstValue("Referrer-Policy").orElse(null))
            assertTrue(downloaded.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment;"))
            assertEquals(404, replay.statusCode())
            assertTrue(source.closed)
            assertEquals(
                FileTransferPhase.COMPLETED,
                server.fileCoordinator.state.value.item(FileTransferId("download-1"))?.phase,
            )
            assertEquals(
                FileTransferPhase.CONNECTING,
                server.fileCoordinator.state.value.item(FileTransferId("download-2"))?.phase,
            )
        }
    }

    @Test
    fun grantRequiresOwnerAndExpiredGrantNeverOpensSource() {
        var opens = 0
        withSessionRouteServer(
            downloadSourceFactory = FileDownloadSourceFactory {
                opens += 1
                RecordingSource(ByteArray(0))
            },
        ) { server ->
            val owner = server.pairBrowser("Chrome")
            val foreign = server.pairBrowser("Edge")
            val payload = "x".encodeToByteArray()
            createDownloadOffers(
                server,
                owner.sessionId,
                payload,
                "download-1" to "../unsafe\".txt",
            )

            val missing = server.request(
                "POST", "/api/v1/files/download-1/download-grant", grantBody(), server.sameOriginJsonHeaders(),
            )
            val foreignResponse = server.request(
                "POST", "/api/v1/files/download-1/download-grant", grantBody(),
                server.sameOriginJsonHeaders(mapOf("Authorization" to "Bearer ${foreign.token}")),
            )
            val issued = server.request(
                "POST", "/api/v1/files/download-1/download-grant", grantBody(),
                server.sameOriginJsonHeaders(mapOf("Authorization" to "Bearer ${owner.token}")),
            )
            val grant = FileProtocolJson.decode<FileDownloadGrantResponse>(issued.body())
            server.advanceGrantClockTo(grant.expiresAt)
            val expired = server.request("GET", grant.downloadPath)

            assertEquals(401, missing.statusCode())
            assertEquals(404, foreignResponse.statusCode())
            assertEquals(404, expired.statusCode())
            assertEquals(0, opens)
        }
    }

    private fun createDownloadOffers(
        server: SessionRouteTestServer,
        sessionId: String,
        payload: ByteArray,
        vararg files: Pair<String, String>,
    ) = runBlocking {
        server.fileCoordinator.create(
            CreateFileTransfersRequest(
                commandId = FileCommandId("android-offer-1"),
                generationId = server.handle.generationId,
                ownerSessionId = BrowserSessionId(sessionId),
                files = files.map { (id, displayName) ->
                    FileTransferMetadata(
                        id = FileTransferId(id),
                        displayName = displayName,
                        sizeBytes = payload.size.toLong(),
                        mimeType = "text/plain",
                        sha256 = sha256(payload),
                        direction = FileTransferDirection.ANDROID_TO_BROWSER,
                    )
                },
            ),
        )
    }

    private class RecordingSource(private val bytes: ByteArray) : FileDownloadSource {
        var closed = false
        override fun inputStream() = ByteArrayInputStream(bytes)
        override suspend fun close() { closed = true }
    }

    private fun grantBody(): String =
        """{"protocolVersion":1,"messageId":"grant-1","type":"file.download_grant.request","timestamp":123}"""

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
