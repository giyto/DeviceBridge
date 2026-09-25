package ru.hznik.devicebridge.domain.settings

import ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES

object SettingsDefaults {
    const val DEFAULT_DEVICE_NAME = "DeviceBridge Android"
    const val DEFAULT_RETENTION_DAYS = 30
    const val MIN_RETENTION_DAYS = 1
    const val MAX_RETENTION_DAYS = 365
}

/**
 * The label of the phone's name on the local network: `<label>.local`. Latin letters, digits
 * and hyphens, 1..40 characters, no hyphen at either end, always lower case.
 */
@JvmInline
value class NetworkName(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid network name" }
    }

    companion object {
        const val MAX_LENGTH = 40

        // Before DEFAULT: the init block of DEFAULT needs it.
        private val PATTERN = Regex("^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$")
        val DEFAULT = NetworkName("devicebridge")

        /** The trimmed, lower-cased label, or null when it breaks the rules. */
        fun parse(input: String): NetworkName? {
            val normalized = input.trim().lowercase()
            return if (PATTERN.matches(normalized)) NetworkName(normalized) else null
        }
    }
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
    val idleStopTimeout: IdleStopTimeout = IdleStopTimeout.DEFAULT,
    /** Serve browsers over HTTPS with the phone's own certificate authority. */
    val secureModeEnabled: Boolean = false,
    val networkName: NetworkName = NetworkName.DEFAULT,
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
    IDLE_STOP_TIMEOUT,
    NETWORK_NAME,
}

/** How long the server may stay without live browser connections before it stops itself. */
enum class IdleStopTimeout(val minutes: Int?, val storageValue: String) {
    OFF(null, "off"),
    MIN_15(15, "15"),
    MIN_30(30, "30"),
    MIN_60(60, "60"),

    /** Only offered by debug builds so E2E checks do not wait 15 minutes. */
    DEBUG_1(1, "debug_1"),
    ;

    companion object {
        val DEFAULT = MIN_30
        val USER_CHOICES = listOf(OFF, MIN_15, MIN_30, MIN_60)

        fun fromStorage(value: String?, allowDebug: Boolean): IdleStopTimeout {
            val parsed = entries.firstOrNull { it.storageValue == value } ?: return DEFAULT
            return if (parsed == DEBUG_1 && !allowDebug) DEFAULT else parsed
        }
    }
}
