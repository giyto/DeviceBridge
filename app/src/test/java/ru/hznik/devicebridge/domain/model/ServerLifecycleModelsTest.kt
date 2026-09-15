package ru.hznik.devicebridge.domain.model

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLifecycleModelsTest {

    @Test
    fun endpointExposesOnlyConcreteHttpAddress() {
        val endpoint = ServerEndpoint(host = "192.168.1.24", port = 8787)

        assertEquals("http://192.168.1.24:8787", endpoint.url)
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpoint(host = "", port = 8787)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpoint(host = "192.168.1.24", port = 0)
        }
    }

    @Test
    fun onlyRunningStateReportsAnActiveServer() {
        val endpoint = ServerEndpoint(host = "192.168.1.24", port = 8787)
        val states = listOf(
            ServerLifecycleState.Stopped,
            ServerLifecycleState.Starting(generation = 1),
            ServerLifecycleState.Stopping(generation = 1),
            ServerLifecycleState.Error(
                generation = 1,
                cause = ServerLifecycleError.NoLanNetwork,
            ),
        )

        states.forEach { state -> assertFalse(state.isRunning) }
        assertTrue(
            ServerLifecycleState.Running(
                generation = 1,
                endpoint = endpoint,
                startedAtElapsedRealtimeMs = 2_000,
            ).isRunning,
        )
    }

    @Test
    fun lifecycleErrorsAndStopReasonsAreTyped() {
        assertTrue(ServerLifecycleError.PermissionRevoked.isRecoverable)
        assertTrue(ServerLifecycleError.AddressChanged.isRecoverable)
        assertEquals(
            "bind failed",
            ServerLifecycleError.Unexpected("bind failed").technicalCause,
        )
        assertEquals(ServerStopReason.UserRequested, ServerStopReason.UserRequested)
        assertEquals(ServerStopReason.NetworkLost, ServerStopReason.NetworkLost)
    }

    @Test
    fun domainSourcesDoNotImportPlatformOrServerFrameworks() {
        val domainRoot = Path.of("src/main/java/ru/hznik/devicebridge/domain")
        val forbiddenImports = Regex(
            """(?m)^import (android\.|androidx\.|io\.ktor\.|dagger\.|javax\.inject\.)""",
        )
        val violations = Files.walk(domainRoot).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.extension == "kt" }
                .filter { forbiddenImports.containsMatchIn(Files.readString(it)) }
                .map { domainRoot.relativize(it).toString() }
                .toList()
        }

        assertTrue("Domain import violations: $violations", violations.isEmpty())
    }
}
