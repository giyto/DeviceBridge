package ru.hznik.devicebridge.diagnostics.server

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticHealthInstrumentedTest {

    @Test
    fun healthRequiresTokenOnAndroidRuntime() = runBlocking {
        val controller = ManagedEmbeddedServerController(
            KtorCioRuntimeFactory(sdkIntProvider = { Build.VERSION.SDK_INT }),
        )
        val port = ServerSocket(0).use { it.localPort }
        controller.start(port)
        val running = controller.state.value as ServerState.Running

        try {
            val authorized = requestHealth(port, "Bearer " + running.token)
            val missing = requestHealth(port, authorization = null)
            val invalid = requestHealth(port, "Bearer invalid")

            assertEquals(200, authorized.code)
            assertTrue(authorized.body.contains("\"status\":\"ok\""))
            assertTrue(
                authorized.body.contains("\"sdkInt\":" + Build.VERSION.SDK_INT),
            )
            assertEquals(401, missing.code)
            assertEquals(401, invalid.code)
            assertFalse(missing.body.contains("ktorVersion"))
            assertFalse(invalid.body.contains("ktorVersion"))
        } finally {
            controller.stop()
        }
    }

    private fun requestHealth(
        port: Int,
        authorization: String?,
    ): HttpResult {
        val connection = URL("http://127.0.0.1:$port/diagnostics/health")
            .openConnection() as HttpURLConnection
        return connection.run {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            if (authorization != null) {
                setRequestProperty("Authorization", authorization)
            }
            try {
                val responseCode = responseCode
                val stream = if (responseCode >= 400) errorStream else inputStream
                HttpResult(
                    code = responseCode,
                    body = stream?.bufferedReader()?.use { it.readText() }.orEmpty(),
                )
            } finally {
                disconnect()
            }
        }
    }

    private data class HttpResult(
        val code: Int,
        val body: String,
    )
}
