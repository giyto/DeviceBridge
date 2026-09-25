package ru.hznik.devicebridge.data.persistence.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.hznik.devicebridge.domain.repository.ThemePreferenceRepository
import ru.hznik.devicebridge.domain.settings.ThemePreference

internal val themePreferenceKey = stringPreferencesKey("theme_preference")

class DataStoreThemePreferenceRepository(
    private val dataStore: DataStore<Preferences>,
) : ThemePreferenceRepository {
    override val themePreference: Flow<ThemePreference?> = dataStore.data
        .orEmptyOnIoError()
        .map { preferences ->
            preferences[themePreferenceKey]
                ?.let { stored -> ThemePreference.entries.firstOrNull { it.name == stored } }
        }
        .distinctUntilChanged()

    override suspend fun updateThemePreference(value: ThemePreference) {
        dataStore.edit { preferences ->
            preferences[themePreferenceKey] = value.name
        }
    }
}
