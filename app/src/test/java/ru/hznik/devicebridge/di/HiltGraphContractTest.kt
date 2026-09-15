package ru.hznik.devicebridge.di

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class HiltGraphContractTest {

    @Test
    fun applicationAndAndroidComponentsDeclareHiltGraph() {
        val manifest = read("src/main/AndroidManifest.xml")
        val application = read(
            "src/main/java/ru/hznik/devicebridge/DeviceBridgeApplication.kt",
        )
        val activity = read("src/main/java/ru/hznik/devicebridge/MainActivity.kt")
        val service = read(
            "src/main/java/ru/hznik/devicebridge/server/ServerForegroundService.kt",
        )

        assertTrue(manifest.contains("android:name=\".DeviceBridgeApplication\""))
        assertTrue(application.contains("@HiltAndroidApp"))
        assertTrue(activity.contains("@AndroidEntryPoint"))
        assertTrue(service.contains("@AndroidEntryPoint"))
    }

    @Test
    fun applicationCoroutineScopeIsInstalledInSingletonGraph() {
        val module = read(
            "src/main/java/ru/hznik/devicebridge/di/ApplicationScopeModule.kt",
        )

        assertTrue(module.contains("@Module"))
        assertTrue(module.contains("@InstallIn(SingletonComponent::class)"))
        assertTrue(module.contains("@Provides"))
        assertTrue(module.contains("@Singleton"))
        assertTrue(module.contains("CoroutineScope"))
        assertTrue(module.contains("SupervisorJob()"))
    }

    private fun read(relativePath: String): String {
        val path = Path.of(relativePath)
        assertTrue("Expected source file: $relativePath", Files.exists(path))
        return Files.readString(path)
    }
}
