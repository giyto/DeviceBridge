package ru.hznik.devicebridge.web

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseWebIsolationContractTest {

    @Test
    fun releaseContainsSafeKtorWebRuntimeWhileDiagnosticsRemainDebugOnly() {
        val buildFile = Files.readString(Path.of("build.gradle.kts"))

        assertTrue(buildFile.contains("implementation(libs.ktor.server.core)"))
        assertTrue(buildFile.contains("implementation(libs.ktor.server.cio)"))
        assertTrue(buildFile.contains("implementation(libs.kotlinx.serialization.json)"))
        assertTrue(Files.exists(Path.of("src/main/assets/web/.gitkeep")))
        assertFalse(Files.exists(Path.of("src/debug/java/ru/hznik/devicebridge/web/WebRoutes.kt")))
        assertTrue(Files.exists(Path.of("src/main/java/ru/hznik/devicebridge/web/WebRoutes.kt")))
        assertTrue(
            Files.exists(
                Path.of(
                    "src/debug/java/ru/hznik/devicebridge/diagnostics/server/DiagnosticRoutes.kt",
                ),
            ),
        )
    }
}
