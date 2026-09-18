package ru.hznik.devicebridge.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES
import ru.hznik.devicebridge.domain.settings.DestinationTree
import ru.hznik.devicebridge.domain.settings.SettingsUpdateResult
import ru.hznik.devicebridge.domain.settings.SettingsValidationError
import ru.hznik.devicebridge.domain.usecase.ObserveSettingsUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateDestinationTreeUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateDeviceNameUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateFileLimitUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateRetentionDaysUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveTrustedBrowsersUseCase
import ru.hznik.devicebridge.domain.usecase.RevokeAllTrustedBrowsersUseCase
import ru.hznik.devicebridge.domain.usecase.RevokeTrustedBrowserUseCase
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId

private const val BYTES_PER_MIB = 1024L * 1024

@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeSettings: ObserveSettingsUseCase,
    private val updateDeviceName: UpdateDeviceNameUseCase,
    private val updateRetentionDays: UpdateRetentionDaysUseCase,
    private val updateDestinationTree: UpdateDestinationTreeUseCase,
    private val updateFileLimit: UpdateFileLimitUseCase,
    observeTrustedBrowsers: ObserveTrustedBrowsersUseCase,
    private val revokeTrustedBrowser: RevokeTrustedBrowserUseCase,
    private val revokeAllTrustedBrowsers: RevokeAllTrustedBrowsersUseCase,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SettingsUiState())
    private val mutableEffects = MutableStateFlow<SettingsEffect?>(null)
    val uiState: StateFlow<SettingsUiState> = mutableUiState
    val effects: StateFlow<SettingsEffect?> = mutableEffects

    init {
        viewModelScope.launch {
            observeSettings().collect { settings ->
                mutableUiState.update { current ->
                    current.copy(
                        settings = settings,
                        isLoading = false,
                        deviceNameInput = settings.deviceName,
                        retentionInput = settings.retentionDays.toString(),
                        fileLimitMiBInput =
                            (settings.effectiveFileLimitBytes / BYTES_PER_MIB).toString(),
                    )
                }
            }
        }
        viewModelScope.launch {
            observeTrustedBrowsers().collect { browsers ->
                mutableUiState.update { current ->
                    current.copy(
                        trustedBrowsers = browsers.map { browser ->
                            TrustedBrowserUiState(
                                id = browser.id,
                                browserLabel = browser.browserLabel,
                                lastUsedAtEpochMillis = browser.lastUsedAtEpochMillis,
                                expiresAtEpochMillis = browser.expiresAtEpochMillis,
                            )
                        },
                    )
                }
            }
        }
    }

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.DeviceNameChanged -> mutableUiState.update {
                it.copy(
                    deviceNameInput = action.value,
                    deviceNameState = it.deviceNameState.copy(errorMessage = null),
                )
            }
            SettingsAction.SaveDeviceName -> saveDeviceName()
            is SettingsAction.RetentionChanged -> mutableUiState.update {
                it.copy(
                    retentionInput = action.value,
                    retentionState = it.retentionState.copy(errorMessage = null),
                )
            }
            SettingsAction.SaveRetention -> saveRetention()
            is SettingsAction.FileLimitMiBChanged -> mutableUiState.update {
                it.copy(
                    fileLimitMiBInput = action.value,
                    fileLimitState = it.fileLimitState.copy(errorMessage = null),
                )
            }
            SettingsAction.SaveFileLimit -> saveFileLimit()
            SettingsAction.ChooseDestination ->
                mutableEffects.value = SettingsEffect.ChooseDestination
            is SettingsAction.DestinationSelected ->
                saveDestination(runCatching { DestinationTree(action.uri) }.getOrNull())
            SettingsAction.DestinationCancelled -> Unit
            SettingsAction.DestinationPermissionUnavailable -> setFieldError(
                SettingField.DESTINATION,
                "Не удалось сохранить доступ к выбранной папке.",
            )
            SettingsAction.ClearDestination -> saveDestination(null)
            is SettingsAction.RevokeTrustedBrowser -> revokeTrusted(action.browserId)
            SettingsAction.RevokeAllTrustedBrowsers -> revokeAllTrusted()
        }
    }

    fun consumeEffect(effect: SettingsEffect) {
        if (mutableEffects.value == effect) {
            mutableEffects.value = null
        }
    }

    private fun saveDeviceName() {
        updateField(
            field = SettingField.DEVICE_NAME,
            operation = { updateDeviceName(mutableUiState.value.deviceNameInput) },
        )
    }

    private fun saveRetention() {
        val value = mutableUiState.value.retentionInput.toIntOrNull()
        if (value == null || value !in 1..365) {
            setFieldError(SettingField.RETENTION, "Введите число от 1 до 365.")
            return
        }
        updateField(
            field = SettingField.RETENTION,
            operation = { updateRetentionDays(value) },
        )
    }

    private fun saveFileLimit() {
        val mebibytes = mutableUiState.value.fileLimitMiBInput.toLongOrNull()
        val bytes = mebibytes?.let {
            runCatching { Math.multiplyExact(it, BYTES_PER_MIB) }.getOrNull()
        }
        if (bytes == null || bytes !in 1..HARD_MAX_FILE_BYTES) {
            setFieldError(SettingField.FILE_LIMIT, "Введите размер от 1 до 1024 МиБ.")
            return
        }
        updateField(
            field = SettingField.FILE_LIMIT,
            operation = { updateFileLimit(bytes) },
        )
    }

    private fun saveDestination(value: DestinationTree?) {
        updateField(
            field = SettingField.DESTINATION,
            operation = { updateDestinationTree(value) },
        )
    }

    private fun revokeTrusted(browserId: TrustedBrowserId) {
        val state = mutableUiState.value
        if (state.trustedBrowsers.none { it.id == browserId }) return
        if (browserId in state.revokingTrustedBrowserIds) return
        mutableUiState.update {
            it.copy(
                revokingTrustedBrowserIds = it.revokingTrustedBrowserIds + browserId,
                trustedBrowsersError = null,
            )
        }
        viewModelScope.launch {
            val revoked = runCatching { revokeTrustedBrowser(browserId) }.getOrDefault(false)
            mutableUiState.update {
                it.copy(
                    revokingTrustedBrowserIds = it.revokingTrustedBrowserIds - browserId,
                    trustedBrowsersError = if (revoked) {
                        null
                    } else {
                        "Не удалось отозвать доступ браузера."
                    },
                )
            }
        }
    }

    private fun revokeAllTrusted() {
        val state = mutableUiState.value
        if (state.trustedBrowsers.isEmpty() || state.revokeAllTrustedBrowsersPending) return
        mutableUiState.update {
            it.copy(revokeAllTrustedBrowsersPending = true, trustedBrowsersError = null)
        }
        viewModelScope.launch {
            val result = runCatching { revokeAllTrustedBrowsers() }
            mutableUiState.update {
                it.copy(
                    revokeAllTrustedBrowsersPending = false,
                    trustedBrowsersError = result.exceptionOrNull()?.let {
                        "Не удалось отозвать доступ браузеров."
                    },
                )
            }
        }
    }

    private fun updateField(
        field: SettingField,
        operation: suspend () -> SettingsUpdateResult,
    ) {
        setFieldState(field, SettingsFieldState(isSaving = true))
        viewModelScope.launch {
            val result = runCatching { operation() }.getOrNull()
            val error = when (result) {
                is SettingsUpdateResult.Updated -> null
                is SettingsUpdateResult.Invalid -> result.reason.message()
                null -> "Не удалось сохранить настройку."
            }
            setFieldState(field, SettingsFieldState(errorMessage = error))
        }
    }

    private fun setFieldError(field: SettingField, message: String) {
        setFieldState(field, SettingsFieldState(errorMessage = message))
    }

    private fun setFieldState(field: SettingField, state: SettingsFieldState) {
        mutableUiState.update { current ->
            when (field) {
                SettingField.DEVICE_NAME -> current.copy(deviceNameState = state)
                SettingField.RETENTION -> current.copy(retentionState = state)
                SettingField.DESTINATION -> current.copy(destinationState = state)
                SettingField.FILE_LIMIT -> current.copy(fileLimitState = state)
            }
        }
    }
}

private enum class SettingField {
    DEVICE_NAME,
    RETENTION,
    DESTINATION,
    FILE_LIMIT,
}

private fun SettingsValidationError.message(): String = when (this) {
    SettingsValidationError.DEVICE_NAME -> "Введите имя длиной от 1 до 40 символов."
    SettingsValidationError.RETENTION_DAYS -> "Введите число от 1 до 365."
    SettingsValidationError.FILE_LIMIT -> "Введите размер от 1 до 1024 МиБ."
}
