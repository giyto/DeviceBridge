package ru.hznik.devicebridge.diagnostics.server

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticWebSocketRouteTest {

    @Test
    fun echoesOneThousandMessagesInOrderWithoutChangingPayload() {
        val result = runCatching {
            runBlocking {
                val controller = ManagedEmbeddedServerController(KtorCioRuntimeFactory())
                val port = ServerSocket(0).use { it.localPort }
                controller.start(port)
                val running = controller.state.value as ServerState.Running
                val expected = List(MESSAGE_COUNT) { index ->
                    "{\"id\":$index,\"payload\":\"message-$index\"}"
                }
                val listener = CollectingListener(MESSAGE_COUNT)

                try {
                    val socket = HttpClient.newHttpClient()
                        .newWebSocketBuilder()
                        .header("Authorization", "Bearer " + running.token)
                        .buildAsync(
                            URI("ws://127.0.0.1:$port/diagnostics/ws"),
                            listener,
                        )
                        .join()

                    expected.forEach { message ->
                        socket.sendText(message, true).join()
                    }

                    assertTrue(
                        "Timed out waiting for WebSocket echoes",
                        listener.awaitMessages(),
                    )
                    assertNull(listener.failure.get())
                    assertEquals(expected, listener.messages)
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
                } finally {
                    controller.stop()
                }
            }
        }

        assertTrue(
            result.exceptionOrNull()?.stackTraceToString() ?: "WebSocket test failed",
            result.isSuccess,
        )
    }

    @Test
    fun abruptDisconnectDoesNotPreventNextClient() {
        val result = runCatching {
            runBlocking {
                val controller = ManagedEmbeddedServerController(KtorCioRuntimeFactory())
                val port = ServerSocket(0).use { it.localPort }
                controller.start(port)
                val token = (controller.state.value as ServerState.Running).token

                try {
                    val firstListener = CollectingListener(expectedMessages = 1)
                    val firstSocket = openSocket(port, token, firstListener)
                    firstSocket.sendText(
                        "{\"id\":1,\"payload\":\"before-abort\"}",
                        true,
                    ).join()
                    assertTrue(firstListener.awaitMessages())
                    firstSocket.abort()

                    val secondListener = CollectingListener(expectedMessages = 1)
                    val secondSocket = openSocket(port, token, secondListener)
                    val expected = "{\"id\":2,\"payload\":\"after-abort\"}"
                    secondSocket.sendText(expected, true).join()

                    assertTrue(secondListener.awaitMessages())
                    assertNull(secondListener.failure.get())
                    assertEquals(listOf(expected), secondListener.messages)
                    secondSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
                } finally {
                    controller.stop()
                }
            }
        }

        assertTrue(
            result.exceptionOrNull()?.stackTraceToString() ?: "Reconnect test failed",
            result.isSuccess,
        )
    }

    private fun openSocket(
        port: Int,
        token: String,
        listener: WebSocket.Listener,
    ): WebSocket {
        return HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .header("Authorization", "Bearer $token")
            .buildAsync(
                URI("ws://127.0.0.1:$port/diagnostics/ws"),
                listener,
            )
            .join()
    }

    private class CollectingListener(
        expectedMessages: Int,
    ) : WebSocket.Listener {
        val messages = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable?>()
        private val latch = CountDownLatch(expectedMessages)
        private val currentMessage = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*>? {
            currentMessage.append(data)
            if (last) {
                messages += currentMessage.toString()
                currentMessage.clear()
                latch.countDown()
            }
            webSocket.request(1)
            return null
        }

        override fun onPing(
            webSocket: WebSocket,
            message: ByteBuffer,
        ): CompletionStage<*> {
            webSocket.request(1)
            return webSocket.sendPong(message)
        }

        override fun onError(
            webSocket: WebSocket,
            error: Throwable,
        ) {
            failure.set(error)
            while (latch.count > 0) {
                latch.countDown()
            }
        }

        fun awaitMessages(): Boolean = latch.await(20, TimeUnit.SECONDS)
    }

    private companion object {
        const val MESSAGE_COUNT = 1_000
    }
}
