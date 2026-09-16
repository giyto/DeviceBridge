package ru.hznik.devicebridge.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.core.protocol.session.SESSION_AUTH_MESSAGE_TYPE
import ru.hznik.devicebridge.core.protocol.session.SESSION_PROTOCOL_VERSION
import ru.hznik.devicebridge.core.protocol.session.SessionProtocolJson
import ru.hznik.devicebridge.core.protocol.session.SessionWebSocketAuthMessage

class SessionWebSocketRouteTest {

    @Test
    fun socketSendsNothingBeforeAuthThenBindsAndClosesOnRevoke() =
        withSessionRouteServer(webSocketAuthTimeoutMs = 1_000) { server ->
            val paired = server.pairBrowser("Chrome")
            val client = client()
            try {
                runBlocking {
                    client.webSocket(
                        request = {
                            url("ws://127.0.0.1:${server.port}/api/v1/events")
                            header(HttpHeaders.Origin, "http://${server.authority}")
                        },
                    ) {
                        assertNull(withTimeoutOrNull(100) { incoming.receive() })
                        send(Frame.Text(authMessage(paired.token)))
                        val acknowledged = (withTimeout(2_000) { incoming.receive() } as Frame.Text)
                            .readText()
                        assertTrue(acknowledged.contains("session.authenticated"))
                        assertFalse(acknowledged.contains(paired.token))

                        server.coordinator.revoke(
                            server.coordinator.state.value.sessions.single().id,
                        )
                        val reason = withTimeout(2_000) { closeReason.await() }
                        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
                    }
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun missingBadAndDuplicateAuthCloseWithPolicyViolation() =
        withSessionRouteServer(webSocketAuthTimeoutMs = 100) { server ->
            val paired = server.pairBrowser("Edge")
            val client = client()
            try {
                runBlocking {
                    val missing = client.webSocketSession {
                        url("ws://127.0.0.1:${server.port}/api/v1/events")
                        header(HttpHeaders.Origin, "http://${server.authority}")
                    }
                    assertEquals(
                        CloseReason.Codes.VIOLATED_POLICY.code,
                        withTimeout(2_000) { missing.closeReason.await() }?.code,
                    )

                    val bad = client.webSocketSession {
                        url("ws://127.0.0.1:${server.port}/api/v1/events")
                        header(HttpHeaders.Origin, "http://${server.authority}")
                    }
                    bad.send(Frame.Text(authMessage("unknown")))
                    assertEquals(
                        CloseReason.Codes.VIOLATED_POLICY.code,
                        withTimeout(2_000) { bad.closeReason.await() }?.code,
                    )

                    val duplicate = client.webSocketSession {
                        url("ws://127.0.0.1:${server.port}/api/v1/events")
                        header(HttpHeaders.Origin, "http://${server.authority}")
                    }
                    val auth = authMessage(paired.token)
                    duplicate.send(Frame.Text(auth))
                    assertTrue(
                        (withTimeout(2_000) { duplicate.incoming.receive() } as Frame.Text)
                            .readText()
                            .contains("session.authenticated"),
                    )
                    duplicate.send(Frame.Text(auth))
                    assertEquals(
                        CloseReason.Codes.VIOLATED_POLICY.code,
                        withTimeout(2_000) { duplicate.closeReason.await() }?.code,
                    )
                }
            } finally {
                client.close()
            }
        }

    private fun client() = HttpClient(CIO) { install(WebSockets) }

    private fun authMessage(token: String): String = SessionProtocolJson.encode(
        SessionWebSocketAuthMessage(
            protocolVersion = SESSION_PROTOCOL_VERSION,
            messageId = "auth-1",
            type = SESSION_AUTH_MESSAGE_TYPE,
            timestamp = 1_000_000,
            token = token,
        ),
    )
}
