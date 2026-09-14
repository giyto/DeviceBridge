package ru.hznik.devicebridge.diagnostics.server

import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleApiShapeTest {

    @Test
    fun lifecycleSeamTypesAreAvailable() {
        val requiredTypes = listOf(
            "ru.hznik.devicebridge.diagnostics.server.ServerState",
            "ru.hznik.devicebridge.diagnostics.server.EmbeddedServerController",
            "ru.hznik.devicebridge.diagnostics.server.DiagnosticServerRuntime",
            "ru.hznik.devicebridge.diagnostics.server.DiagnosticServerRuntimeFactory",
            "ru.hznik.devicebridge.diagnostics.server.ManagedEmbeddedServerController",
        )

        val missingTypes = requiredTypes.filterNot { className ->
            runCatching {
                Class.forName(className, false, javaClass.classLoader)
            }.isSuccess
        }

        assertTrue("Missing lifecycle seam types: $missingTypes", missingTypes.isEmpty())
    }
}
