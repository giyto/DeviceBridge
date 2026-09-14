package ru.hznik.devicebridge.diagnostics.server

import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorCioRuntimeFactoryTest {

    @Test
    fun startsCioAndReleasesPortAfterStop() {
        val result = runCatching {
            runBlocking {
                val factoryClass = Class.forName(
                    "ru.hznik.devicebridge.diagnostics.server.KtorCioRuntimeFactory",
                    false,
                    javaClass.classLoader,
                )
                val factory = factoryClass.getDeclaredConstructor().newInstance()
                assertTrue(factory is DiagnosticServerRuntimeFactory)
                val port = findFreePort()

                val runtime = (factory as DiagnosticServerRuntimeFactory).start(
                    preferredPort = port,
                    token = "test-token",
                )
                try {
                    Socket("127.0.0.1", runtime.port).use { socket ->
                        assertTrue(socket.isConnected)
                    }
                } finally {
                    runtime.stop()
                }

                ServerSocket(port).use { rebound ->
                    assertTrue(rebound.isBound)
                }
            }
        }

        assertTrue(
            result.exceptionOrNull()?.stackTraceToString() ?: "Ktor/CIO runtime failed",
            result.isSuccess,
        )
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}
