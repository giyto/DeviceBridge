package ru.hznik.devicebridge.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.hznik.devicebridge.domain.settings.DestinationTree
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.settings.IdleStopTimeout
import ru.hznik.devicebridge.domain.settings.SettingsUpdateResult

interface SettingsRepository {
    val settings: Flow<DeviceSettings>

    suspend fun updateDeviceName(value: String): SettingsUpdateResult

    suspend fun updateRetentionDays(value: Int): SettingsUpdateResult

    suspend fun updateDestinationTree(value: DestinationTree?): SettingsUpdateResult

    suspend fun updateEffectiveFileLimitBytes(value: Long): SettingsUpdateResult

    suspend fun updateAutoAcceptTrustedFiles(enabled: Boolean): SettingsUpdateResult

    suspend fun updateIdleStopTimeout(value: IdleStopTimeout): SettingsUpdateResult

    suspend fun updateSecureMode(enabled: Boolean): SettingsUpdateResult
}
