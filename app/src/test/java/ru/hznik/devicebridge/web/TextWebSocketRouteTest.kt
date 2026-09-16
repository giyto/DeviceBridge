package ru.hznik.devicebridge.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.websocket.close
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.core.protocol.session.SESSION_AUTH_MESSAGE_TYPE
import ru.hznik.devicebridge.core.protocol.session.SESSION_PROTOCOL_VERSION
import ru.hznik.devicebridge.core.protocol.session.SessionProtocolJson
import ru.hznik.devicebridge.core.protocol.session.SessionWebSocketAuthMessage
import ru.hznik.devicebridge.core.protocol.text.TEXT_ACK_TYPE
import ru.hznik.devicebridge.core.protocol.text.TEXT_ERROR_TYPE
import ru.hznik.devicebridge.core.protocol.text.TEXT_RECEIVED_TYPE
import ru.hznik.devicebridge.core.protocol.text.TEXT_SNAPSHOT_TYPE
import ru.hznik.devicebridge.core.protocol.text.TextAcknowledgementMessage
import ru.hznik.devicebridge.core.protocol.text.TextErrorEvent
import ru.hznik.devicebridge.core.protocol.text.TextProtocolJson
import ru.hznik.devicebridge.core.protocol.text.TextReceivedEvent
import ru.hznik.devicebridge.core.protocol.text.TextSnapshotEvent
import ru.hznik.devicebridge.domain.text.SendTextRequest
import ru.hznik.devicebridge.domain.text.TextTransferResult
import ru.hznik.devicebridge.domain.text.TextTransferRejection
import ru.hznik.devicebridge.domain.text.TextTransferStatus

class TextWebSocketRouteTest {

    @Test
    fun authenticatedSocketReceivesOnlyItsAddressedEventAndAcknowledgesDelivery() =
        withSessionRouteServer { server ->
            val yandex = server.pairBrowser("Яндекс Браузер")
            val edge = server.pairBrowser("Edge")
            val client = client()
            try {
                runBlocking {
                    val yandexSocket = client.authorizedSocket(server, yandex.token, "auth-yandex")
                    val edgeSocket = client.authorizedSocket(server, edge.token, "auth-edge")
                    receiveAuthAndEmptySnapshot(yandexSocket)
                    receiveAuthAndEmptySnapshot(edgeSocket)

                    val delivery = async {
                        server.textCoordinator.send(
                            SendTextRequest(
                                sessionId = server.coordinator.state.value.sessions
                                    .single { it.browserLabel == "Яндекс Браузер" }
                                    .id,
                                content = "только для Яндекса",
                            ),
                        )
                    }
                    val event = TextProtocolJson.decode<TextReceivedEvent>(
                        (withTimeout(2_000) { yandexSocket.incoming.receive() } as Frame.Text)
                            .readText(),
                    )

                    assertEquals(TEXT_RECEIVED_TYPE, event.type)
                    assertEquals("только для Яндекса", event.content)
                    assertNull(withTimeoutOrNull(200) { edgeSocket.incoming.receive() })
                    yandexSocket.send(Frame.Text(ack(event.messageId)))

                    val item = (delivery.await() as TextTransferResult.Accepted).item
                    assertEquals(TextTransferStatus.DELIVERED, item.status)
                    yandexSocket.close()
                    edgeSocket.close()
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun reauthenticatedSocketReceivesSessionScopedSnapshotWithoutDuplicates() =
        withSessionRouteServer { server ->
            val paired = server.pairBrowser("Chrome")
            val headers = server.sameOriginJsonHeaders(
                mapOf("Authorization" to "Bearer ${paired.token}"),
            )
            val body = """{"protocolVersion":1,"messageId":"incoming-1","type":"text.send","timestamp":123,"content":"hello"}"""
            assertEquals(200, server.request("POST", "/api/v1/text", body, headers).statusCode())
            assertEquals(200, server.request("POST", "/api/v1/text", body, headers).statusCode())

            val client = client()
            try {
                runBlocking {
                    val socket = client.authorizedSocket(server, paired.token, "auth-refresh")
                    assertTrue(receiveText(socket).contains("session.authenticated"))
                    val snapshot = TextProtocolJson.decode<TextSnapshotEvent>(receiveText(socket))

                    assertEquals(TEXT_SNAPSHOT_TYPE, snapshot.type)
                    assertEquals(listOf("incoming-1"), snapshot.items.map { it.messageId })
                    socket.close()
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun invalidTextControlMessageReturnsProtocolError() = withSessionRouteServer { server ->
        val paired = server.pairBrowser("Firefox")
        val client = client()
        try {
            runBlocking {
                val socket = client.authorizedSocket(server, paired.token, "auth-firefox")
                receiveAuthAndEmptySnapshot(socket)
                socket.send(
                    Frame.Text(
                        """{"protocolVersion":99,"messageId":"ack-1","type":"text.ack","timestamp":123,"acknowledgedMessageId":"missing"}""",
                    ),
                )

                val error = TextProtocolJson.decode<TextErrorEvent>(receiveText(socket))
                assertEquals(TEXT_ERROR_TYPE, error.type)
                socket.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun revokedSessionFailsPendingDeliveryAndCannotReceiveAnotherMessage() =
        withSessionRouteServer { server ->
            val paired = server.pairBrowser("Edge")
            val session = server.coordinator.state.value.sessions.single()
            val client = client()
            try {
                runBlocking {
                    val socket = client.authorizedSocket(server, paired.token, "auth-revoke")
                    receiveAuthAndEmptySnapshot(socket)
                    val pending = async {
                        server.textCoordinator.send(SendTextRequest(session.id, "pending"))
                    }
                    TextProtocolJson.decode<TextReceivedEvent>(receiveText(socket))

                    server.coordinator.revoke(session.id)

                    val failed = (pending.await() as TextTransferResult.Accepted).item
                    assertEquals(TextTransferStatus.FAILED, failed.status)
                    assertEquals(
                        TextTransferResult.Rejected(TextTransferRejection.SESSION_UNAVAILABLE),
                        server.textCoordinator.send(SendTextRequest(session.id, "after revoke")),
                    )
                }
            } finally {
                client.close()
            }
        }

    private suspend fun HttpClient.authorizedSocket(
        server: SessionRouteTestServer,
        token: String,
        authMessageId: String,
    ) = webSocketSession {
        url("ws://127.0.0.1:${server.port}/api/v1/events")
        header(HttpHeaders.Origin, "http://${server.authority}")
    }.also { socket ->
        socket.send(Frame.Text(auth(token, authMessageId)))
    }

    private suspend fun receiveAuthAndEmptySnapshot(
        socket: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
    ) {
        assertTrue(receiveText(socket).contains("session.authenticated"))
        val snapshot = TextProtocolJson.decode<TextSnapshotEvent>(receiveText(socket))
        assertEquals(TEXT_SNAPSHOT_TYPE, snapshot.type)
        assertTrue(snapshot.items.isEmpty())
    }

    private suspend fun receiveText(
        socket: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
    ): String = (withTimeout(2_000) { socket.incoming.receive() } as Frame.Text).readText()

    private fun auth(token: String, messageId: String): String = SessionProtocolJson.encode(
        SessionWebSocketAuthMessage(
            protocolVersion = SESSION_PROTOCOL_VERSION,
            messageId = messageId,
            type = SESSION_AUTH_MESSAGE_TYPE,
            timestamp = 1_000_000,
            token = token,
        ),
    )

    private fun ack(messageId: String): String = TextProtocolJson.encode(
        TextAcknowledgementMessage(
            protocolVersion = 1,
            messageId = "ack-$messageId",
            type = TEXT_ACK_TYPE,
            timestamp = 1_000_001,
            acknowledgedMessageId = messageId,
        ),
    )

    private fun client() = HttpClient(CIO) { install(WebSockets) }
}
