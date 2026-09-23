package ru.hznik.devicebridge.domain.settings

import ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES

object SettingsDefaults {
    const val DEFAULT_DEVICE_NAME = "DeviceBridge Android"
    const val DEFAULT_RETENTION_DAYS = 30
    const val MIN_RETENTION_DAYS = 1
    const val MAX_RETENTION_DAYS = 365
}

@JvmInline
value class DestinationTree(val value: String) {
    init {
        require(value.isNotBlank())
        require(value.length <= 2_048)
        require(value.none(Char::isISOControl))
    }
}

data class DeviceSettings(
    val deviceName: String,
    val retentionDays: Int,
    val destinationTree: DestinationTree?,
    val effectiveFileLimitBytes: Long,
    val autoAcceptTrustedFiles: Boolean = false,
) {
    init {
        require(deviceName.isNotBlank())
        require(deviceName.codePointCount(0, deviceName.length) <= 40)
        require(deviceName.none(Char::isISOControl))
        require(retentionDays in SettingsDefaults.MIN_RETENTION_DAYS..SettingsDefaults.MAX_RETENTION_DAYS)
        require(effectiveFileLimitBytes in 1..HARD_MAX_FILE_BYTES)
    }

    companion object {
        fun defaults(): DeviceSettings = DeviceSettings(
            deviceName = SettingsDefaults.DEFAULT_DEVICE_NAME,
            retentionDays = SettingsDefaults.DEFAULT_RETENTION_DAYS,
            destinationTree = null,
            effectiveFileLimitBytes = HARD_MAX_FILE_BYTES,
        )
    }
}

sealed interface SettingsUpdateResult {
    data class Updated(val settings: DeviceSettings) : SettingsUpdateResult
    data class Invalid(val reason: SettingsValidationError) : SettingsUpdateResult
}

enum class SettingsValidationError {
    DEVICE_NAME,
    RETENTION_DAYS,
    FILE_LIMIT,
    AUTO_ACCEPT_DESTINATION,
}
