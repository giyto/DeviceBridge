package ru.hznik.devicebridge.web

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseWebIsolationContractTest {

    @Test
    fun releaseContainsSafeKtorWebRuntimeAndNoDebugOnlyServerCode() {
        val buildFile = Files.readString(Path.of("build.gradle.kts"))
        val viteConfig = Files.readString(Path.of("../web/vite.config.ts"))

        assertTrue(buildFile.contains("implementation(libs.ktor.server.core)"))
        assertTrue(buildFile.contains("implementation(libs.ktor.server.cio)"))
        assertTrue(buildFile.contains("implementation(libs.ktor.server.websockets)"))
        assertTrue(buildFile.contains("implementation(libs.kotlinx.serialization.json)"))
        assertFalse(buildFile.contains("debugImplementation(libs.ktor.server.websockets)"))
        assertTrue(buildFile.contains("outputs.dir(webOutputDirectory)"))
        assertTrue(viteConfig.contains("fileName: \".gitkeep\""))
        assertTrue(Files.exists(Path.of("src/main/java/ru/hznik/devicebridge/web/WebRoutes.kt")))
        assertFalse(Files.exists(Path.of("src/debug/java")))
    }
}
