package ru.hznik.devicebridge.data.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.persistence.datastore.DataStoreSettingsRepository
import ru.hznik.devicebridge.domain.settings.DestinationTree
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.settings.IdleStopTimeout
import ru.hznik.devicebridge.domain.settings.SettingsUpdateResult
import ru.hznik.devicebridge.domain.settings.SettingsValidationError
import ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES

class DataStoreSettingsRepositoryTest {
    @Test
    fun firstReadUsesSafeDefaults() = runTest {
        val repository = repository()

        assertEquals(DeviceSettings.defaults(), repository.settings.first())
    }

    @Test
    fun validValuesPersistInOneSettingsSnapshot() = runTest {
        val repository = repository()

        assertTrue(repository.updateDeviceName("  Pixel 8  ") is SettingsUpdateResult.Updated)
        assertTrue(repository.updateRetentionDays(90) is SettingsUpdateResult.Updated)
        assertTrue(
            repository.updateDestinationTree(
                DestinationTree("content://documents/tree/devicebridge"),
            ) is SettingsUpdateResult.Updated,
        )
        assertTrue(
            repository.updateEffectiveFileLimitBytes(512L * 1024 * 1024) is
                SettingsUpdateResult.Updated,
        )

        assertEquals(
            DeviceSettings(
                deviceName = "Pixel 8",
                retentionDays = 90,
                destinationTree = DestinationTree("content://documents/tree/devicebridge"),
                effectiveFileLimitBytes = 512L * 1024 * 1024,
            ),
            repository.settings.first(),
        )
    }

    @Test
    fun everyInvalidFieldKeepsItsLastValidValue() = runTest {
        val repository = repository()
        repository.updateDeviceName("Pixel")
        repository.updateRetentionDays(45)
        repository.updateEffectiveFileLimitBytes(128L * 1024 * 1024)

        listOf("", "   ", "a".repeat(41), "bad\nname").forEach { invalidName ->
            assertTrue(repository.updateDeviceName(invalidName) is SettingsUpdateResult.Invalid)
        }
        listOf(0, 366).forEach { invalidRetention ->
            assertTrue(
                repository.updateRetentionDays(invalidRetention) is SettingsUpdateResult.Invalid,
            )
        }
        listOf(0L, HARD_MAX_FILE_BYTES + 1).forEach { invalidLimit ->
            assertTrue(
                repository.updateEffectiveFileLimitBytes(invalidLimit) is
                    SettingsUpdateResult.Invalid,
            )
        }

        assertEquals(
            DeviceSettings(
                deviceName = "Pixel",
                retentionDays = 45,
                destinationTree = null,
                effectiveFileLimitBytes = 128L * 1024 * 1024,
            ),
            repository.settings.first(),
        )
    }

    @Test
    fun emptyStoreUsesTheInjectedPhysicalDeviceName() = runTest {
        val repository = DataStoreSettingsRepository(
            InMemoryPreferencesDataStore(),
            defaultDeviceName = "Google Pixel 8",
        )

        assertEquals("Google Pixel 8", repository.settings.first().deviceName)
    }

    @Test
    fun storedUserNameWinsOverAChangedPhysicalDeviceFallback() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val first = DataStoreSettingsRepository(dataStore, defaultDeviceName = "Google Pixel 8")
        first.updateDeviceName("Мой телефон")

        val recreated = DataStoreSettingsRepository(
            dataStore,
            defaultDeviceName = "Samsung Galaxy S26",
        )

        assertEquals("Мой телефон", recreated.settings.first().deviceName)
    }

    @Test
    fun legacyDefaultNameMigratesToPhysicalNameWithoutChangingOtherSettings() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val legacyRepository = DataStoreSettingsRepository(dataStore)
        legacyRepository.updateDeviceName("DeviceBridge Android")
        legacyRepository.updateRetentionDays(90)
        legacyRepository.updateDestinationTree(
            DestinationTree("content://documents/tree/devicebridge"),
        )
        legacyRepository.updateEffectiveFileLimitBytes(512L * 1024 * 1024)

        val migrated = DataStoreSettingsRepository(
            dataStore,
            defaultDeviceName = "Xiaomi 22081212UG",
        ).settings.first()

        assertEquals("Xiaomi 22081212UG", migrated.deviceName)
        assertEquals(90, migrated.retentionDays)
        assertEquals(
            DestinationTree("content://documents/tree/devicebridge"),
            migrated.destinationTree,
        )
        assertEquals(512L * 1024 * 1024, migrated.effectiveFileLimitBytes)
    }
    @Test
    fun autoAcceptIsOffByDefaultAndCannotBeEnabledWithoutDestination() = runTest {
        val repository = repository()

        assertEquals(false, repository.settings.first().autoAcceptTrustedFiles)
        assertEquals(
            SettingsUpdateResult.Invalid(SettingsValidationError.AUTO_ACCEPT_DESTINATION),
            repository.updateAutoAcceptTrustedFiles(true),
        )
        assertEquals(false, repository.settings.first().autoAcceptTrustedFiles)
    }

    @Test
    fun autoAcceptPersistsWithDestinationAndSurvivesRecreation() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val first = DataStoreSettingsRepository(dataStore)
        first.updateDestinationTree(DestinationTree("content://documents/tree/devicebridge"))

        assertTrue(first.updateAutoAcceptTrustedFiles(true) is SettingsUpdateResult.Updated)
        assertEquals(true, DataStoreSettingsRepository(dataStore).settings.first().autoAcceptTrustedFiles)

        assertTrue(first.updateAutoAcceptTrustedFiles(false) is SettingsUpdateResult.Updated)
        assertEquals(false, DataStoreSettingsRepository(dataStore).settings.first().autoAcceptTrustedFiles)
    }

    @Test
    fun idleStopDefaultsToThirtyMinutesAndPersistsEveryUserChoice() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val repository = DataStoreSettingsRepository(dataStore)

        assertEquals(IdleStopTimeout.MIN_30, repository.settings.first().idleStopTimeout)
        IdleStopTimeout.USER_CHOICES.forEach { choice ->
            assertTrue(repository.updateIdleStopTimeout(choice) is SettingsUpdateResult.Updated)
            assertEquals(choice, DataStoreSettingsRepository(dataStore).settings.first().idleStopTimeout)
        }
    }

    @Test
    fun debugIdleStopIsRejectedAndIgnoredOutsideDebugBuilds() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val release = DataStoreSettingsRepository(dataStore)
        val debug = DataStoreSettingsRepository(dataStore, allowDebugIdleTimeout = true)

        assertEquals(
            SettingsUpdateResult.Invalid(SettingsValidationError.IDLE_STOP_TIMEOUT),
            release.updateIdleStopTimeout(IdleStopTimeout.DEBUG_1),
        )
        assertTrue(debug.updateIdleStopTimeout(IdleStopTimeout.DEBUG_1) is SettingsUpdateResult.Updated)
        assertEquals(IdleStopTimeout.DEBUG_1, debug.settings.first().idleStopTimeout)
        assertEquals(IdleStopTimeout.MIN_30, release.settings.first().idleStopTimeout)
    }

    @Test
    fun unknownStoredIdleStopFallsBackToDefault() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[stringPreferencesKey("idle_stop_timeout")] = "45"
            }
        }

        assertEquals(
            IdleStopTimeout.MIN_30,
            DataStoreSettingsRepository(dataStore).settings.first().idleStopTimeout,
        )
    }

    private fun repository(): DataStoreSettingsRepository =
        DataStoreSettingsRepository(InMemoryPreferencesDataStore())

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
