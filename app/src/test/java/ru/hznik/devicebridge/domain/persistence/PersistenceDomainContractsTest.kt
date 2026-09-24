package ru.hznik.devicebridge.domain.persistence

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import ru.hznik.devicebridge.domain.repository.HistoryRepository
import ru.hznik.devicebridge.domain.repository.SettingsRepository
import ru.hznik.devicebridge.domain.repository.TrustedBrowserRepository
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.settings.SettingsDefaults
import ru.hznik.devicebridge.domain.trust.TrustedBrowser
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId
import ru.hznik.devicebridge.domain.history.HistoryPersistenceEvent
import ru.hznik.devicebridge.feature.home.ServerSessionUiState
import ru.hznik.devicebridge.feature.settings.SettingsUiState

class PersistenceDomainContractsTest {
    @Test
    fun settingsExposeSafeFirstRunDefaults() {
        val settings = DeviceSettings.defaults()

        assertEquals(30, settings.retentionDays)
        assertEquals(1_073_741_824L, settings.effectiveFileLimitBytes)
        assertNull(settings.destinationTree)
        assertEquals(SettingsDefaults.DEFAULT_DEVICE_NAME, settings.deviceName)
    }

    @Test
    fun trustedBrowserContainsMetadataButNoRawCredential() {
        val browser = TrustedBrowser(
            id = TrustedBrowserId("trusted-1"),
            browserLabel = "Yandex Browser",
            createdAtEpochMillis = 1_700_000_000_000,
            lastUsedAtEpochMillis = null,
            expiresAtEpochMillis = 1_702_592_000_000,
        )

        assertEquals("Yandex Browser", browser.browserLabel)
        assertFalse(
            TrustedBrowser::class.java.declaredFields.any {
                it.name.contains("credential", ignoreCase = true) ||
                    it.name.contains("secret", ignoreCase = true) ||
                    it.name.contains("verifier", ignoreCase = true)
            },
        )
    }

    @Test
    fun persistenceAndHostUiModelsExposeNoSessionOrStorageSecrets() {
        val forbidden = listOf(
            "credential",
            "sessiontoken",
            "bearer",
            "sourceuri",
            "filesystempath",
            "fulltext",
            "payload",
        )
        val fieldNames = listOf(
            TrustedBrowser::class.java,
            ServerSessionUiState::class.java,
            SettingsUiState::class.java,
            HistoryPersistenceEvent.WriteFailed::class.java,
        ).flatMap { type -> type.declaredFields.map { it.name.lowercase() } }

        assertFalse(fieldNames.any { field -> forbidden.any(field::contains) })
    }

    @Test
    fun persistenceRepositoriesAreSmallDomainInterfaces() {
        val sources = listOf(
            "HistoryRepository.kt",
            "SettingsRepository.kt",
            "TrustedBrowserRepository.kt",
        ).map { fileName ->
            Files.readString(
                Path.of("src/main/java/ru/hznik/devicebridge/domain/repository/$fileName"),
            )
        }.joinToString()

        assertFalse(sources.contains("android."))
        assertFalse(sources.contains("androidx.room"))
        assertFalse(sources.contains("androidx.datastore"))
    }
}
