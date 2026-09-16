package ru.hznik.devicebridge.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.core.protocol.file.FILE_OFFER_TYPE
import ru.hznik.devicebridge.core.protocol.file.FILE_PROGRESS_TYPE
import ru.hznik.devicebridge.core.protocol.file.FILE_SNAPSHOT_TYPE
import ru.hznik.devicebridge.core.protocol.file.FileOfferMessage
import ru.hznik.devicebridge.core.protocol.file.FileProgressEvent
import ru.hznik.devicebridge.core.protocol.file.FileProtocolJson
import ru.hznik.devicebridge.core.protocol.file.FileSnapshotEvent
import ru.hznik.devicebridge.core.protocol.session.SESSION_AUTH_MESSAGE_TYPE
import ru.hznik.devicebridge.core.protocol.session.SESSION_PROTOCOL_VERSION
import ru.hznik.devicebridge.core.protocol.session.SessionProtocolJson
import ru.hznik.devicebridge.core.protocol.session.SessionWebSocketAuthMessage
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileCommandId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferEvent
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata
import ru.hznik.devicebridge.domain.session.BrowserSessionId

class FileWebSocketRouteTest {

    @Test
    fun reconnectGetsOwnedSnapshotAndLiveOfferProgressEvents() =
        withSessionRouteServer(enableFileEvents = true) { server ->
            val paired = server.pairBrowser("Chrome")
            create(server, paired.sessionId, "existing")
            val client = HttpClient(CIO) { install(WebSockets) }
            try {
                runBlocking {
                    val socket = client.webSocketSession {
                        url("ws://127.0.0.1:${server.port}/api/v1/events")
                        header(HttpHeaders.Origin, "http://${server.authority}")
                    }
                    socket.send(Frame.Text(auth(paired.token)))
                    assertTrue(receive(socket).contains("session.authenticated"))
                    assertTrue(receive(socket).contains("text.snapshot"))
                    val snapshot = FileProtocolJson.decode<FileSnapshotEvent>(receive(socket))
                    assertEquals(FILE_SNAPSHOT_TYPE, snapshot.type)
                    assertEquals(listOf("existing"), snapshot.items.map { it.metadata.transferId })

                    create(server, paired.sessionId, "live")
                    val offer = FileProtocolJson.decode<FileOfferMessage>(receive(socket))
                    assertEquals(FILE_OFFER_TYPE, offer.type)
                    assertEquals("live", offer.items.single().transferId)

                    server.fileCoordinator.transition(FileTransferId("existing"), FileTransferEvent.Started)
                    server.fileCoordinator.transition(FileTransferId("existing"), FileTransferEvent.Progressed(1, 12))
                    var progress: FileProgressEvent? = null
                    for (attempt in 0 until 4) {
                        val payload = receive(socket)
                        if (payload.contains(FILE_PROGRESS_TYPE)) {
                            val candidate = FileProtocolJson.decode<FileProgressEvent>(payload)
                            if (candidate.bytesTransferred == 1L) {
                                progress = candidate
                                break
                            }
                        }
                    }
                    assertEquals(1L, progress?.bytesTransferred)
                    assertEquals(12L, progress?.speedBytesPerSecond)
                    socket.close()
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun closingAuthenticatedSocketCancelsOwnedNonTerminalTransfers() =
        withSessionRouteServer(enableFileEvents = true) { server ->
            val paired = server.pairBrowser("Chrome")
            create(server, paired.sessionId, "disconnect-file")
            val client = HttpClient(CIO) { install(WebSockets) }
            try {
                runBlocking {
                    val socket = client.webSocketSession {
                        url("ws://127.0.0.1:${server.port}/api/v1/events")
                        header(HttpHeaders.Origin, "http://${server.authority}")
                    }
                    socket.send(Frame.Text(auth(paired.token)))
                    repeat(3) { receive(socket) }
                    socket.close()
                    withTimeout(2_000) {
                        while (
                            server.fileCoordinator.state.value
                                .item(FileTransferId("disconnect-file"))?.phase !=
                            ru.hznik.devicebridge.domain.file.FileTransferPhase.CANCELLED
                        ) {
                            delay(10)
                        }
                    }
                }
            } finally {
                client.close()
            }
        }

    private fun create(server: SessionRouteTestServer, sessionId: String, id: String) = runBlocking {
        server.fileCoordinator.create(
            CreateFileTransfersRequest(
                commandId = FileCommandId("command-$id"),
                generationId = server.handle.generationId,
                ownerSessionId = BrowserSessionId(sessionId),
                files = listOf(
                    FileTransferMetadata(
                        id = FileTransferId(id),
                        displayName = "$id.bin",
                        sizeBytes = 1,
                        mimeType = "application/octet-stream",
                        sha256 = "a".repeat(64),
                        direction = FileTransferDirection.ANDROID_TO_BROWSER,
                    ),
                ),
            ),
        )
        delay(30)
    }

    private suspend fun receive(socket: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession): String =
        (withTimeout(2_000) { socket.incoming.receive() } as Frame.Text).readText()

    private fun auth(token: String): String = SessionProtocolJson.encode(
        SessionWebSocketAuthMessage(
            protocolVersion = SESSION_PROTOCOL_VERSION,
            messageId = "auth-files",
            type = SESSION_AUTH_MESSAGE_TYPE,
            timestamp = 1_000_000,
            token = token,
        ),
    )
}
