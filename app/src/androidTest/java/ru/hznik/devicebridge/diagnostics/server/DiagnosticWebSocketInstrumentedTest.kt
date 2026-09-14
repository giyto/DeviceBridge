package ru.hznik.devicebridge.diagnostics.server

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.net.ServerSocket
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticWebSocketInstrumentedTest {

    @Test
    fun websocketPreservesOneThousandMessagesAndRecoversAfterAbort() = runBlocking {
        val controller = ManagedEmbeddedServerController(
            KtorCioRuntimeFactory(sdkIntProvider = { Build.VERSION.SDK_INT }),
        )
        val port = ServerSocket(0).use { it.localPort }
        controller.start(port)
        val token = (controller.state.value as ServerState.Running).token
        val client = HttpClient(CIO) {
            install(WebSockets)
        }

        try {
            client.webSocket(
                request = {
                    url("ws://127.0.0.1:$port/diagnostics/ws")
                    header(HttpHeaders.Authorization, "Bearer $token")
                },
            ) {
                repeat(MESSAGE_COUNT) { index ->
                    val expected = "{\"id\":$index,\"payload\":\"message-$index\"}"
                    send(Frame.Text(expected))
                    val actual = (incoming.receive() as Frame.Text).readText()
                    assertEquals(expected, actual)
                }
            }

            val aborted = client.webSocketSession {
                url("ws://127.0.0.1:$port/diagnostics/ws")
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            aborted.send(Frame.Text("{\"id\":1,\"payload\":\"abort\"}"))
            assertEquals(
                "{\"id\":1,\"payload\":\"abort\"}",
                (aborted.incoming.receive() as Frame.Text).readText(),
            )
            aborted.cancel()

            client.webSocket(
                request = {
                    url("ws://127.0.0.1:$port/diagnostics/ws")
                    header(HttpHeaders.Authorization, "Bearer $token")
                },
            ) {
                val expected = "{\"id\":2,\"payload\":\"reconnected\"}"
                send(Frame.Text(expected))
                assertEquals(expected, (incoming.receive() as Frame.Text).readText())
            }
        } finally {
            client.close()
            controller.stop()
        }
    }

    private companion object {
        const val MESSAGE_COUNT = 1_000
    }
}
