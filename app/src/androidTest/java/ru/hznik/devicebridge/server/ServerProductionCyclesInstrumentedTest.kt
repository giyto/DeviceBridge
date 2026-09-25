package ru.hznik.devicebridge.server

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoints
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.data.server.ServerLifecycleCoordinator
import ru.hznik.devicebridge.di.ServerLifecycleEntryPoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.model.ServerStopReason

@RunWith(AndroidJUnit4::class)
class ServerProductionCyclesInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val coordinator: ServerLifecycleCoordinator
        get() = EntryPoints.get(
            context.applicationContext,
            ServerLifecycleEntryPoint::class.java,
        ).coordinator()

    @Before
    fun prepare() {
        grantRuntimePermissions()
        runBlocking { coordinator.stop(ServerStopReason.UserRequested) }
    }

    @After
    fun cleanup() {
        context.startService(serviceIntent(ServerForegroundService.ACTION_STOP))
        awaitState { it is ServerLifecycleState.Stopped }
    }

    @Test
    fun twentyProductionServiceCyclesServeWebShellAndReleaseEveryPort() {
        repeat(20) { iteration ->
            ContextCompat.startForegroundService(
                context,
                serviceIntent(ServerForegroundService.ACTION_START),
            )
            val running = awaitState {
                it is ServerLifecycleState.Running
            } as ServerLifecycleState.Running

            val root = request(running, "/")
            val manifest = request(running, "/web-manifest.json")
            assertTrue("cycle=$iteration root=$root", root.startsWith("HTTP/1.1 200"))
            assertTrue("cycle=$iteration", root.contains("DeviceBridge"))
            assertTrue(
                "cycle=$iteration manifest=$manifest",
                manifest.startsWith("HTTP/1.1 200"),
            )
            assertTrue("cycle=$iteration", manifest.contains("protocolVersion"))

            context.startService(serviceIntent(ServerForegroundService.ACTION_STOP))
            awaitState { it is ServerLifecycleState.Stopped }
            assertFalse("cycle=$iteration port stayed open", canConnect(running.endpoint.port))
        }
    }

    private fun request(
        state: ServerLifecycleState.Running,
        path: String,
    ): String = Socket("127.0.0.1", state.endpoint.port).use { socket ->
        socket.soTimeout = TEST_TIMEOUT_MILLIS.toInt()
        val writer = socket.getOutputStream().bufferedWriter()
        writer.write("GET $path HTTP/1.1\r\n")
        // The address the phone serves: its name while it holds one, otherwise its IP.
        writer.write("Host: ${state.endpoint.authorities.single()}\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.flush()
        socket.getInputStream().bufferedReader().readText()
    }

    private fun canConnect(port: Int): Boolean = runCatching {
        Socket("127.0.0.1", port).use { it.isConnected }
    }.getOrDefault(false)

    private fun serviceIntent(action: String) =
        Intent(context, ServerForegroundService::class.java).setAction(action)

    private fun awaitState(
        predicate: (ServerLifecycleState) -> Boolean,
    ): ServerLifecycleState {
        val deadline = SystemClock.elapsedRealtime() + TEST_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            coordinator.state.value.let { state ->
                if (predicate(state)) return state
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        error("Timed out waiting for server state; actual=${coordinator.state.value}")
    }

    private fun grantRuntimePermissions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        if (Build.VERSION.SDK_INT >= 37) {
            automation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            )
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 10_000L
        const val POLL_INTERVAL_MILLIS = 50L
    }
}
