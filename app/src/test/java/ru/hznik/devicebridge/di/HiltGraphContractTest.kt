package ru.hznik.devicebridge.di

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
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

    @Test
    fun persistenceDependenciesAreInstalledAsProcessSingletons() {
        val module = read(
            "src/main/java/ru/hznik/devicebridge/di/PersistenceModule.kt",
        )

        assertTrue(module.contains("@Module"))
        assertTrue(module.contains("@InstallIn(SingletonComponent::class)"))
        assertTrue(module.contains("fun provideDeviceBridgeDatabase"))
        assertTrue(module.contains("fun provideSettingsDataStore"))
        assertTrue(module.contains("fun provideSettingsRepository"))
        assertTrue(module.contains("fun provideHistoryRepository"))
        assertTrue(module.contains("@ApplicationScope"))
        assertTrue(module.countOccurrences("@Singleton") >= 5)
    }

    @Test
    fun textTransferDependenciesAreInstalledInSingletonGraphWithoutUiOrKtorInDomain() {
        val module = read(
            "src/main/java/ru/hznik/devicebridge/di/ServerLifecycleModule.kt",
        )
        val repository = read(
            "src/main/java/ru/hznik/devicebridge/domain/repository/TextTransferRepository.kt",
        )
        val useCases = read(
            "src/main/java/ru/hznik/devicebridge/domain/usecase/TextTransferUseCases.kt",
        )

        assertTrue(module.contains("provideSessionEventDispatcher"))
        assertTrue(module.contains("provideTextTransferCoordinator"))
        assertTrue(module.contains("provideTextTransferRepository"))
        assertTrue(module.contains("provideObserveTextTransfersUseCase"))
        assertTrue(module.contains("provideSendTextToBrowserUseCase"))
        assertTrue(module.contains("provideReceiveTextFromBrowserUseCase"))
        assertTrue(module.contains("provideRetryTextTransferUseCase"))
        listOf(repository, useCases).forEach { source ->
            assertTrue(!source.contains("io.ktor"))
            assertTrue(!source.contains("android."))
            assertTrue(!source.contains("androidx.compose"))
        }
    }

    @Test
    fun fileTransferDependenciesUseOneProcessCoordinatorAndAndroidAdaptersStayOutsideDomain() {
        val module = read(
            "src/main/java/ru/hznik/devicebridge/di/ServerLifecycleModule.kt",
        )
        val repository = read(
            "src/main/java/ru/hznik/devicebridge/domain/repository/FileTransferRepository.kt",
        )
        val useCases = read(
            "src/main/java/ru/hznik/devicebridge/domain/usecase/FileTransferUseCases.kt",
        )

        assertTrue(module.contains("@Singleton\n        fun provideFileTransferCoordinator"))
        assertTrue(module.contains("provideFileTransferRepository"))
        assertTrue(module.contains("provideFileSessionEventBridge"))
        assertTrue(module.contains("bindFileTransferWifiLock"))
        assertTrue(module.contains("provideFileUploadTargetFactory"))
        assertTrue(module.contains("provideFileDownloadSourceFactory"))
        assertTrue(module.contains("provideObserveFileTransfersUseCase"))
        assertTrue(module.contains("provideCreateFileTransfersUseCase"))
        assertTrue(module.contains("provideApproveFileTransferUseCase"))
        assertTrue(module.contains("provideCancelFileTransferUseCase"))
        assertTrue(module.contains("provideRetryFileTransferUseCase"))
        assertTrue(module.contains("provideVerifyFileTransferUseCase"))
        listOf(repository, useCases).forEach { source ->
            assertTrue(!source.contains("io.ktor"))
            assertTrue(!source.contains("android."))
            assertTrue(!source.contains("androidx.compose"))
            assertTrue(!source.contains("ContentResolver"))
        }
    }

    @Test
    fun fileSelectionPreparationOwnsSourceRegistrationAndUsesSingletonRegistry() {
        val activity = read("src/main/java/ru/hznik/devicebridge/MainActivity.kt")
        val app = read("src/main/java/ru/hznik/devicebridge/app/DeviceBridgeApp.kt")
        val viewModel = read("src/main/java/ru/hznik/devicebridge/feature/file/FileViewModel.kt")

        assertTrue(activity.contains("lateinit var fileSourceRegistry: FileSourceRegistry"))
        assertTrue(app.contains("fileSourceRegistry: FileSourceRegistry? = null"))
        assertTrue(app.contains("sourceRegistry = effectiveFileSourceRegistry"))
        assertTrue(app.contains("stageTemporarySources = true"))
        assertFalse(viewModel.contains("FileSourceRegistry"))
    }

    @Test
    fun productionServerReadsEffectiveFileLimitFromSettingsSnapshot() {
        val runtime = read(
            "src/main/java/ru/hznik/devicebridge/data/server/KtorServerRuntimeFactory.kt",
        )

        assertTrue(runtime.contains("ObserveSettingsUseCase"))
        assertTrue(runtime.contains("settingsState.value.effectiveFileLimitBytes"))
        assertTrue(runtime.contains("effectiveFileLimitBytes ="))
    }

    private fun read(relativePath: String): String {
        val path = Path.of(relativePath)
        assertTrue("Expected source file: $relativePath", Files.exists(path))
        return Files.readString(path)
    }

    private fun String.countOccurrences(value: String): Int =
        windowed(value.length).count { it == value }
}
