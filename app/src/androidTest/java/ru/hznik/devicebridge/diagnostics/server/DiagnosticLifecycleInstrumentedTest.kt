package ru.hznik.devicebridge.diagnostics.server

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticLifecycleInstrumentedTest {

    @Test
    fun twentyStartHealthStopCyclesReleasePort() = runBlocking {
        val controller = ManagedEmbeddedServerController(
            KtorCioRuntimeFactory(sdkIntProvider = { Build.VERSION.SDK_INT }),
        )
        val port = ServerSocket(0).use { it.localPort }

        repeat(20) { iteration ->
            controller.start(port)
            val running = controller.state.value as ServerState.Running
            assertEquals(
                "Health failed on iteration $iteration",
                200,
                healthCode(port, running.token),
            )

            controller.stop()

            assertSame(ServerState.Stopped, controller.state.value)
            ServerSocket(port).use { rebound ->
                check(rebound.isBound) { "Port was not released on iteration $iteration" }
            }
        }
    }

    private fun healthCode(
        port: Int,
        token: String,
    ): Int {
        val connection = URL("http://127.0.0.1:$port/diagnostics/health")
            .openConnection() as HttpURLConnection
        return connection.run {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            try {
                responseCode
            } finally {
                disconnect()
            }
        }
    }
}
