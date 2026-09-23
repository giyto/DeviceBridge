package ru.hznik.devicebridge.feature.settings

import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.settings.ThemePreference
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId

enum class SettingsLoadState {
    LOADING,
    CONTENT,
    ERROR,
}

enum class DestinationAvailability {
    NONE,
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
}

enum class AutoAcceptStatus {
    NO_DESTINATION,
    OFF,
    ON,
    PAUSED,
}

data class SettingsFieldState(
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val isDirty: Boolean = false,
)

data class SettingsUiState(
    val settings: DeviceSettings = DeviceSettings.defaults(),
    val loadState: SettingsLoadState = SettingsLoadState.LOADING,
    val loadErrorMessage: String? = null,
    val deviceNameInput: String = DeviceSettings.defaults().deviceName,
    val retentionInput: String = DeviceSettings.defaults().retentionDays.toString(),
    val fileLimitMiBInput: String =
        (DeviceSettings.defaults().effectiveFileLimitBytes / (1024 * 1024)).toString(),
    val deviceNameState: SettingsFieldState = SettingsFieldState(),
    val retentionState: SettingsFieldState = SettingsFieldState(),
    val destinationState: SettingsFieldState = SettingsFieldState(),
    val fileLimitState: SettingsFieldState = SettingsFieldState(),
    val autoAcceptState: SettingsFieldState = SettingsFieldState(),
    val trustedBrowsers: List<TrustedBrowserUiState> = emptyList(),
    val destinationAvailability: DestinationAvailability = DestinationAvailability.NONE,
    val revokingTrustedBrowserIds: Set<TrustedBrowserId> = emptySet(),
    val revokeAllTrustedBrowsersPending: Boolean = false,
    val trustedBrowsersError: String? = null,
    val themePreference: ThemePreference? = null,
) {
    val autoAcceptStatus: AutoAcceptStatus
        get() = when {
            settings.destinationTree == null -> AutoAcceptStatus.NO_DESTINATION
            !settings.autoAcceptTrustedFiles -> AutoAcceptStatus.OFF
            destinationAvailability == DestinationAvailability.UNAVAILABLE -> AutoAcceptStatus.PAUSED
            else -> AutoAcceptStatus.ON
        }
}

data class TrustedBrowserUiState(
    val id: TrustedBrowserId,
    val browserLabel: String,
    val lastUsedAtEpochMillis: Long?,
    val expiresAtEpochMillis: Long,
)

sealed interface SettingsAction {
    data class DeviceNameChanged(val value: String) : SettingsAction
    data object SaveDeviceName : SettingsAction
    data object RetryLoad : SettingsAction
    data class RetentionChanged(val value: String) : SettingsAction
    data object SaveRetention : SettingsAction
    data class FileLimitMiBChanged(val value: String) : SettingsAction
    data object SaveFileLimit : SettingsAction
    data object ChooseDestination : SettingsAction
    data class DestinationSelected(val uri: String) : SettingsAction
    data object DestinationCancelled : SettingsAction
    data object DestinationPermissionUnavailable : SettingsAction
    data object ClearDestination : SettingsAction
    data class RevokeTrustedBrowser(val browserId: TrustedBrowserId) : SettingsAction
    data class DestinationAvailabilityChecked(
        val uri: String,
        val isAvailable: Boolean,
    ) : SettingsAction
    data object RevokeAllTrustedBrowsers : SettingsAction
    data class ThemeSelected(val value: ThemePreference) : SettingsAction
    data class AutoAcceptToggled(val enabled: Boolean) : SettingsAction
}

sealed interface SettingsEffect {
    data object ChooseDestination : SettingsEffect
}
