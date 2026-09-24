package ru.hznik.devicebridge.web

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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
            assertEquals("bytes", downloaded.headers().firstValue("Accept-Ranges").orElse(null))
            assertEquals("\"sha256-${sha256(payload)}\"", downloaded.headers().firstValue("ETag").orElse(null))
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

    @Test
    fun interruptedDownloadContinuesWithARangeOfTheSameGrant() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val cut = 70_000
        var opens = 0
        withSessionRouteServer(
            downloadSourceFactory = FileDownloadSourceFactory {
                opens += 1
                if (opens == 1) BreakingSource(payload, cut) else RecordingSource(payload)
            },
        ) { server ->
            val paired = server.pairBrowser("Chrome")
            createDownloadOffers(server, paired.sessionId, payload, "download-1" to "movie.bin")
            val path = issueGrant(server, paired.token, "download-1")

            runCatching { server.download(path) }
            val id = FileTransferId("download-1")
            waitUntil { server.fileCoordinator.state.value.item(id)?.phase == FileTransferPhase.FAILED }
            val delivered = server.fileCoordinator.state.value.item(id)!!.bytesTransferred
            assertTrue(delivered in 1..cut.toLong())

            val etag = "\"sha256-${sha256(payload)}\""
            val resumed = server.download(
                path,
                headers = mapOf("Range" to "bytes=$delivered-", "If-Range" to etag),
            )

            assertEquals(206, resumed.statusCode())
            assertEquals(
                "bytes $delivered-${payload.size - 1}/${payload.size}",
                resumed.headers().firstValue("Content-Range").orElse(null),
            )
            assertEquals(etag, resumed.headers().firstValue("ETag").orElse(null))
            assertArrayEquals(payload.copyOfRange(delivered.toInt(), payload.size), resumed.body())
            assertEquals(FileTransferPhase.COMPLETED, server.fileCoordinator.state.value.item(id)?.phase)
            assertEquals(404, server.download(path, headers = mapOf("Range" to "bytes=0-")).statusCode())
        }
    }

    @Test
    fun rangesThatCannotContinueAreRefusedWithoutContent() {
        val payload = ByteArray(100_000) { 7 }
        var opens = 0
        withSessionRouteServer(
            downloadSourceFactory = FileDownloadSourceFactory {
                opens += 1
                BreakingSource(payload, 10)
            },
        ) { server ->
            val paired = server.pairBrowser("Chrome")
            createDownloadOffers(
                server,
                paired.sessionId,
                payload,
                "download-1" to "first.bin",
                "download-2" to "second.bin",
            )
            val path = issueGrant(server, paired.token, "download-1")
            runCatching { server.download(path) }
            val id = FileTransferId("download-1")
            waitUntil { server.fileCoordinator.state.value.item(id)?.phase == FileTransferPhase.FAILED }

            fun ranged(range: String, ifRange: String? = null) = server.download(
                path,
                headers = buildMap {
                    put("Range", range)
                    ifRange?.let { put("If-Range", it) }
                },
            )

            val beyond = ranged("bytes=${payload.size}-")
            val multi = ranged("bytes=0-1,5-9")
            val otherValidator = ranged("bytes=5-", "\"sha256-${"0".repeat(64)}\"")
            val dateValidator = ranged("bytes=5-", "Wed, 21 Oct 2015 07:28:00 GMT")
            // The failed download released the queue to the next item.
            val busy = ranged("bytes=5-")
            val foreignGrant = server.download(
                "/api/v1/files/download-1?grant=" + "A".repeat(22),
                headers = mapOf("Range" to "bytes=5-"),
            )

            assertEquals(416, beyond.statusCode())
            assertEquals("bytes */${payload.size}", beyond.headers().firstValue("Content-Range").orElse(null))
            assertEquals(416, multi.statusCode())
            assertEquals(412, otherValidator.statusCode())
            assertEquals(412, dateValidator.statusCode())
            assertEquals(409, busy.statusCode())
            assertEquals(404, foreignGrant.statusCode())
            listOf(beyond, multi, otherValidator, dateValidator, busy, foreignGrant).forEach { response ->
                assertFalse(response.body().any { it == 7.toByte() })
            }
            assertEquals(1, opens)
            assertEquals(FileTransferPhase.FAILED, server.fileCoordinator.state.value.item(id)?.phase)

            runBlocking { server.fileCoordinator.cancel(FileTransferId("download-2")) }
            runBlocking { server.fileCoordinator.close(server.handle.generationId) }
            assertEquals(404, ranged("bytes=5-").statusCode())
            assertEquals(1, opens)
        }
    }

    @Test
    fun parsesOnlySingleByteRangesAndStrongShaValidators() {
        assertEquals(ByteRangeRequest.Single(5, null), parseByteRange("bytes=5-"))
        assertEquals(ByteRangeRequest.Single(0, 9), parseByteRange("bytes=0-9"))
        listOf("bytes=-5", "bytes=9-0", "bytes=0-1,3-4", "items=0-", "bytes=a-").forEach { value ->
            assertEquals(value, ByteRangeRequest.Unsupported, parseByteRange(value))
        }
        val hex = "ab".repeat(32)
        assertEquals(hex, sha256FromEtag("\"sha256-$hex\""))
        assertEquals(null, sha256FromEtag("W/\"sha256-$hex\""))
        assertEquals(null, sha256FromEtag("sha256-$hex"))
    }

    private fun issueGrant(server: SessionRouteTestServer, token: String, id: String): String {
        val response = server.request(
            "POST",
            "/api/v1/files/$id/download-grant",
            grantBody(),
            server.sameOriginJsonHeaders(mapOf("Authorization" to "Bearer $token")),
        )
        assertEquals(200, response.statusCode())
        return FileProtocolJson.decode<FileDownloadGrantResponse>(response.body()).downloadPath
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condition not reached" }
            Thread.sleep(10)
        }
    }

    /** Gives [cut] bytes, then fails like a dropped connection. */
    private class BreakingSource(private val bytes: ByteArray, private val cut: Int) : FileDownloadSource {
        override fun inputStream() = object : java.io.InputStream() {
            private var position = 0
            override fun read(): Int {
                if (position >= cut) throw java.io.IOException("connection reset")
                return bytes[position++].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position >= cut) throw java.io.IOException("connection reset")
                val count = minOf(len, cut - position)
                System.arraycopy(bytes, position, b, off, count)
                position += count
                return count
            }
        }
        override suspend fun close() = Unit
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
