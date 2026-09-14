package ru.hznik.devicebridge.web

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseWebIsolationContractTest {

    @Test
    fun ktorRemainsDebugOnlyWhileReleasePackagesStaticAssets() {
        val buildFile = Files.readString(Path.of("build.gradle.kts"))

        assertTrue(buildFile.contains("debugImplementation(libs.ktor.server.core)"))
        assertTrue(buildFile.contains("debugImplementation(libs.ktor.server.cio)"))
        assertFalse(
            Regex("""(?m)^\s*implementation\(libs\.ktor\.server""").containsMatchIn(buildFile),
        )
        assertTrue(Files.exists(Path.of("src/main/assets/web/.gitkeep")))
        assertTrue(Files.exists(Path.of("src/debug/java/ru/hznik/devicebridge/web/WebRoutes.kt")))
        assertFalse(Files.exists(Path.of("src/main/java/ru/hznik/devicebridge/web/WebRoutes.kt")))
    }
}
