package ru.hznik.devicebridge.data.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.hznik.devicebridge.data.persistence.datastore.DataStoreThemePreferenceRepository
import ru.hznik.devicebridge.domain.settings.ThemePreference

class DataStoreThemePreferenceRepositoryTest {
    @Test
    fun firstReadHasNoChoiceSoAppFollowsSystemTheme() = runTest {
        val repository = DataStoreThemePreferenceRepository(InMemoryPreferencesDataStore())

        assertNull(repository.themePreference.first())
    }

    @Test
    fun selectedThemePersists() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        DataStoreThemePreferenceRepository(dataStore).updateThemePreference(ThemePreference.LIGHT)

        assertEquals(
            ThemePreference.LIGHT,
            DataStoreThemePreferenceRepository(dataStore).themePreference.first(),
        )
    }

    @Test
    fun legacyOrUnknownStoredValueMeansNoChoice() = runTest {
        listOf("SYSTEM", "SEPIA").forEach { stored ->
            val dataStore = InMemoryPreferencesDataStore(
                mutablePreferencesOf(stringPreferencesKey("theme_preference") to stored),
            )

            assertNull(DataStoreThemePreferenceRepository(dataStore).themePreference.first())
        }
    }

    private class InMemoryPreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)

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
