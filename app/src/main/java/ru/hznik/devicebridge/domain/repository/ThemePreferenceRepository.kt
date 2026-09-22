package ru.hznik.devicebridge.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.hznik.devicebridge.domain.settings.ThemePreference

interface ThemePreferenceRepository {
    /** Emits `null` until the user picks a theme, so the app follows the system theme. */
    val themePreference: Flow<ThemePreference?>

    suspend fun updateThemePreference(value: ThemePreference)
}
