package ru.hznik.devicebridge.feature.settings

import ru.hznik.devicebridge.data.file.PartialUploadSummary
import ru.hznik.devicebridge.data.tls.RootCertificateStatus
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.settings.IdleStopTimeout
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
        bytesToMebibytesInput(DeviceSettings.defaults().effectiveFileLimitBytes),
    val deviceNameState: SettingsFieldState = SettingsFieldState(),
    val networkNameInput: String = DeviceSettings.defaults().networkName.value,
    val networkNameState: SettingsFieldState = SettingsFieldState(),
    /** The saved name waits for the next server start. */
    val networkNameAppliesAfterRestart: Boolean = false,
    /**
     * False when secure mode is on and the phone's root, made before custom names, does not
     * cover the chosen name: only a reset lets HTTPS work by that name.
     */
    val certificateCoversNetworkName: Boolean = true,
    val retentionState: SettingsFieldState = SettingsFieldState(),
    val destinationState: SettingsFieldState = SettingsFieldState(),
    val fileLimitState: SettingsFieldState = SettingsFieldState(),
    val autoAcceptState: SettingsFieldState = SettingsFieldState(),
    val idleStopState: SettingsFieldState = SettingsFieldState(),
    val trustedBrowsers: List<TrustedBrowserUiState> = emptyList(),
    val destinationAvailability: DestinationAvailability = DestinationAvailability.NONE,
    val revokingTrustedBrowserIds: Set<TrustedBrowserId> = emptySet(),
    val revokeAllTrustedBrowsersPending: Boolean = false,
    val trustedBrowsersError: String? = null,
    val themePreference: ThemePreference? = null,
    val partialUploads: PartialUploadSummary = PartialUploadSummary(count = 0, totalBytes = 0),
    val discardPartialUploadsPending: Boolean = false,
    val partialUploadsError: String? = null,
    val secureModeState: SettingsFieldState = SettingsFieldState(),
    val rootCertificate: RootCertificateStatus = RootCertificateStatus.NotCreated,
    /** A change that waits for the user to agree, because it restarts the server or resets trust. */
    val pendingSecureModeChange: SecureModeChange? = null,
    val certificateResetPending: Boolean = false,
    /** Set after a reset, until dismissed: the old root must be removed from computers. */
    val certificateWasReset: Boolean = false,
    val certificateShareError: String? = null,
) {
    val autoAcceptStatus: AutoAcceptStatus
        get() = when {
            settings.destinationTree == null -> AutoAcceptStatus.NO_DESTINATION
            !settings.autoAcceptTrustedFiles -> AutoAcceptStatus.OFF
            destinationAvailability == DestinationAvailability.UNAVAILABLE -> AutoAcceptStatus.PAUSED
            else -> AutoAcceptStatus.ON
        }
}

private const val BYTES_PER_MIB = 1024L * 1024

/** The file limit field's MiB as bytes, or null when it is not a whole number or overflows. */
internal fun mebibytesInputToBytes(input: String): Long? = input.toLongOrNull()?.let { mebibytes ->
    runCatching { Math.multiplyExact(mebibytes, BYTES_PER_MIB) }.getOrNull()
}

/** A byte limit as the whole MiB shown in the file limit field. */
internal fun bytesToMebibytesInput(bytes: Long): String = (bytes / BYTES_PER_MIB).toString()

sealed interface SecureModeChange {
    data class Toggle(val enabled: Boolean) : SecureModeChange
    data object ResetCertificate : SecureModeChange
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
    data class NetworkNameChanged(val value: String) : SettingsAction
    data object SaveNetworkName : SettingsAction
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
    data object DiscardPartialUploads : SettingsAction
    data class ThemeSelected(val value: ThemePreference) : SettingsAction
    data class AutoAcceptToggled(val enabled: Boolean) : SettingsAction
    data class IdleStopSelected(val value: IdleStopTimeout) : SettingsAction
    data class SecureModeToggled(val enabled: Boolean) : SettingsAction
    data object ResetCertificateClicked : SettingsAction
    data object SecureModeChangeConfirmed : SettingsAction
    data object SecureModeChangeDismissed : SettingsAction
    data object CertificateResetNoticeDismissed : SettingsAction
    data object ShareCertificateClicked : SettingsAction
}

sealed interface SettingsEffect {
    data object ChooseDestination : SettingsEffect

    /** Opens the system share sheet for the root certificate at [contentUri]. */
    data class ShareCertificate(val contentUri: String) : SettingsEffect
}
