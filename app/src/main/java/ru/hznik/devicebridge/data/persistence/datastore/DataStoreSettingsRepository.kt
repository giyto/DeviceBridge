package ru.hznik.devicebridge.data.persistence.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES
import ru.hznik.devicebridge.domain.repository.SettingsRepository
import ru.hznik.devicebridge.domain.settings.DestinationTree
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.settings.IdleStopTimeout
import ru.hznik.devicebridge.domain.settings.NetworkName
import ru.hznik.devicebridge.domain.settings.SettingsDefaults
import ru.hznik.devicebridge.domain.settings.SettingsUpdateResult
import ru.hznik.devicebridge.domain.settings.SettingsValidationError
import ru.hznik.devicebridge.domain.settings.isValidDeviceName

internal object SettingsPreferenceKeys {
    val deviceName = stringPreferencesKey("device_name")
    val retentionDays = intPreferencesKey("retention_days")
    val destinationTree = stringPreferencesKey("destination_tree_uri")
    val effectiveFileLimitBytes = longPreferencesKey("effective_file_limit_bytes")
    val autoAcceptTrustedFiles = booleanPreferencesKey("auto_accept_trusted_files")
    val idleStopTimeout = stringPreferencesKey("idle_stop_timeout")
    val secureModeEnabled = booleanPreferencesKey("secure_mode_enabled")
    val networkName = stringPreferencesKey("network_name")
}

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    defaultDeviceName: String = SettingsDefaults.DEFAULT_DEVICE_NAME,
) : SettingsRepository {
    private val initialDeviceName = defaultDeviceName.trim().takeIf(::isValidDeviceName)
        ?: SettingsDefaults.DEFAULT_DEVICE_NAME
    override val settings: Flow<DeviceSettings> = dataStore.data
        .orEmptyOnIoError()
        .map(::mapSettings)

    override suspend fun updateDeviceName(value: String): SettingsUpdateResult {
        val normalized = value.trim()
        if (!isValidDeviceName(normalized)) {
            return SettingsUpdateResult.Invalid(SettingsValidationError.DEVICE_NAME)
        }
        return update { preferences ->
            preferences[SettingsPreferenceKeys.deviceName] = normalized
        }
    }

    override suspend fun updateRetentionDays(value: Int): SettingsUpdateResult {
        if (value !in SettingsDefaults.MIN_RETENTION_DAYS..SettingsDefaults.MAX_RETENTION_DAYS) {
            return SettingsUpdateResult.Invalid(SettingsValidationError.RETENTION_DAYS)
        }
        return update { preferences ->
            preferences[SettingsPreferenceKeys.retentionDays] = value
        }
    }

    override suspend fun updateDestinationTree(
        value: DestinationTree?,
    ): SettingsUpdateResult = update { preferences ->
        if (value == null) {
            preferences.remove(SettingsPreferenceKeys.destinationTree)
        } else {
            preferences[SettingsPreferenceKeys.destinationTree] = value.value
        }
    }

    override suspend fun updateEffectiveFileLimitBytes(value: Long): SettingsUpdateResult {
        if (value !in 1..HARD_MAX_FILE_BYTES) {
            return SettingsUpdateResult.Invalid(SettingsValidationError.FILE_LIMIT)
        }
        return update { preferences ->
            preferences[SettingsPreferenceKeys.effectiveFileLimitBytes] = value
        }
    }

    override suspend fun updateAutoAcceptTrustedFiles(enabled: Boolean): SettingsUpdateResult {
        var rejected = false
        val result = update { preferences ->
            if (enabled && preferences[SettingsPreferenceKeys.destinationTree] == null) {
                rejected = true
            } else {
                preferences[SettingsPreferenceKeys.autoAcceptTrustedFiles] = enabled
            }
        }
        return if (rejected) {
            SettingsUpdateResult.Invalid(SettingsValidationError.AUTO_ACCEPT_DESTINATION)
        } else {
            result
        }
    }

    override suspend fun updateIdleStopTimeout(value: IdleStopTimeout): SettingsUpdateResult =
        update { preferences ->
            preferences[SettingsPreferenceKeys.idleStopTimeout] = value.storageValue
        }

    override suspend fun updateSecureMode(enabled: Boolean): SettingsUpdateResult =
        update { preferences ->
            preferences[SettingsPreferenceKeys.secureModeEnabled] = enabled
        }

    override suspend fun updateNetworkName(value: String): SettingsUpdateResult {
        val name = NetworkName.parse(value)
            ?: return SettingsUpdateResult.Invalid(SettingsValidationError.NETWORK_NAME)
        return update { preferences ->
            preferences[SettingsPreferenceKeys.networkName] = name.value
        }
    }

    private suspend fun update(
        transform: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ): SettingsUpdateResult {
        val updated = dataStore.edit { preferences ->
            transform(preferences)
        }
        return SettingsUpdateResult.Updated(mapSettings(updated))
    }

    private fun mapSettings(preferences: Preferences): DeviceSettings {
        val defaults = DeviceSettings.defaults().copy(deviceName = initialDeviceName)
        val deviceName = preferences[SettingsPreferenceKeys.deviceName]
            ?.trim()
            ?.takeIf(::isValidDeviceName)
            ?.takeUnless { candidate ->
                candidate == SettingsDefaults.DEFAULT_DEVICE_NAME
            }
            ?: defaults.deviceName
        val retentionDays = preferences[SettingsPreferenceKeys.retentionDays]
            ?.takeIf {
                it in SettingsDefaults.MIN_RETENTION_DAYS..SettingsDefaults.MAX_RETENTION_DAYS
            }
            ?: defaults.retentionDays
        val destinationTree = preferences[SettingsPreferenceKeys.destinationTree]
            ?.let { stored -> runCatching { DestinationTree(stored) }.getOrNull() }
        val effectiveFileLimitBytes = preferences[SettingsPreferenceKeys.effectiveFileLimitBytes]
            ?.takeIf { it in 1..HARD_MAX_FILE_BYTES }
            ?: defaults.effectiveFileLimitBytes
        return DeviceSettings(
            deviceName = deviceName,
            retentionDays = retentionDays,
            destinationTree = destinationTree,
            effectiveFileLimitBytes = effectiveFileLimitBytes,
            autoAcceptTrustedFiles =
                preferences[SettingsPreferenceKeys.autoAcceptTrustedFiles] ?: false,
            idleStopTimeout = IdleStopTimeout.fromStorage(preferences[SettingsPreferenceKeys.idleStopTimeout]),
            secureModeEnabled = preferences[SettingsPreferenceKeys.secureModeEnabled] ?: false,
            networkName = preferences[SettingsPreferenceKeys.networkName]
                ?.let(NetworkName::parse)
                ?: NetworkName.DEFAULT,
        )
    }
}
