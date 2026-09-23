package ru.hznik.devicebridge.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES
import ru.hznik.devicebridge.domain.repository.ThemePreferenceRepository
import ru.hznik.devicebridge.domain.settings.DestinationTree
import ru.hznik.devicebridge.domain.settings.SettingsUpdateResult
import ru.hznik.devicebridge.domain.settings.SettingsValidationError
import ru.hznik.devicebridge.domain.usecase.ObserveSettingsUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateAutoAcceptTrustedFilesUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateIdleStopTimeoutUseCase
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModel @Inject constructor(
    private val observeSettings: ObserveSettingsUseCase,
    private val updateDeviceName: UpdateDeviceNameUseCase,
    private val updateRetentionDays: UpdateRetentionDaysUseCase,
    private val updateDestinationTree: UpdateDestinationTreeUseCase,
    private val updateFileLimit: UpdateFileLimitUseCase,
    private val updateAutoAcceptTrustedFiles: UpdateAutoAcceptTrustedFilesUseCase,
    private val updateIdleStopTimeout: UpdateIdleStopTimeoutUseCase,
    observeTrustedBrowsers: ObserveTrustedBrowsersUseCase,
    private val revokeTrustedBrowser: RevokeTrustedBrowserUseCase,
    private val revokeAllTrustedBrowsers: RevokeAllTrustedBrowsersUseCase,
    private val themePreferenceRepository: ThemePreferenceRepository,
) : ViewModel() {
    private sealed interface LoadResult {
        data object Loading : LoadResult
        data class Loaded(val settings: ru.hznik.devicebridge.domain.settings.DeviceSettings) :
            LoadResult
        data object Failed : LoadResult
    }
    private val mutableUiState = MutableStateFlow(SettingsUiState())
    private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED)
    val uiState: StateFlow<SettingsUiState> = mutableUiState
    private val settingsReloadRevision = MutableStateFlow(0)
    val effects = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            settingsReloadRevision
                .flatMapLatest {
                    observeSettings()
                        .map<ru.hznik.devicebridge.domain.settings.DeviceSettings, LoadResult>(
                            LoadResult::Loaded,
                        )
                        .onStart { emit(LoadResult.Loading) }
                        .catch { emit(LoadResult.Failed) }
                }
                .collect(::applyLoadResult)
        }
        viewModelScope.launch {
            themePreferenceRepository.themePreference.collect { preference ->
                mutableUiState.update { it.copy(themePreference = preference) }
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

    private fun applyLoadResult(result: LoadResult) {
        mutableUiState.update { current ->
            when (result) {
                LoadResult.Loading -> current.copy(
                    loadState = SettingsLoadState.LOADING,
                    loadErrorMessage = null,
                )
                is LoadResult.Loaded -> {
                    val settings = result.settings
                    val destinationAvailability = when {
                        settings.destinationTree == null -> DestinationAvailability.NONE
                        current.settings.destinationTree == settings.destinationTree &&
                            current.destinationAvailability != DestinationAvailability.NONE ->
                            current.destinationAvailability
                        else -> DestinationAvailability.CHECKING
                    }
                    current.copy(
                        settings = settings,
                        loadState = SettingsLoadState.CONTENT,
                        loadErrorMessage = null,
                        deviceNameInput = if (current.deviceNameState.isDirty) {
                            current.deviceNameInput
                        } else {
                            settings.deviceName
                        },
                        retentionInput = if (current.retentionState.isDirty) {
                            current.retentionInput
                        } else {
                            settings.retentionDays.toString()
                        },
                        fileLimitMiBInput = if (current.fileLimitState.isDirty) {
                            current.fileLimitMiBInput
                        } else {
                            (settings.effectiveFileLimitBytes / BYTES_PER_MIB).toString()
                        },
                        destinationAvailability = destinationAvailability,
                    )
                }
                LoadResult.Failed -> current.copy(
                    loadState = SettingsLoadState.ERROR,
                    loadErrorMessage = "Не удалось прочитать локальные настройки.",
                )
            }
        }
    }

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.DeviceNameChanged -> mutableUiState.update {
                it.copy(
                    deviceNameInput = action.value,
                    deviceNameState = it.deviceNameState.copy(
                        errorMessage = null,
                        isDirty = action.value.trim() != it.settings.deviceName,
                    ),
                )
            }
            SettingsAction.SaveDeviceName -> saveDeviceName()
            is SettingsAction.ThemeSelected -> {
                mutableUiState.update { it.copy(themePreference = action.value) }
                viewModelScope.launch {
                    themePreferenceRepository.updateThemePreference(action.value)
                }
            }
            SettingsAction.RetryLoad -> settingsReloadRevision.update(Int::inc)
            is SettingsAction.RetentionChanged -> mutableUiState.update {
                it.copy(
                    retentionInput = action.value,
                    retentionState = it.retentionState.copy(
                        errorMessage = null,
                        isDirty = action.value.toIntOrNull() != it.settings.retentionDays,
                    ),
                )
            }
            SettingsAction.SaveRetention -> saveRetention()
            is SettingsAction.FileLimitMiBChanged -> mutableUiState.update {
                val bytes = action.value.toLongOrNull()?.let { mebibytes ->
                    runCatching { Math.multiplyExact(mebibytes, BYTES_PER_MIB) }.getOrNull()
                }
                it.copy(
                    fileLimitMiBInput = action.value,
                    fileLimitState = it.fileLimitState.copy(
                        errorMessage = null,
                        isDirty = bytes != it.settings.effectiveFileLimitBytes,
                    ),
                )
            }
            SettingsAction.SaveFileLimit -> saveFileLimit()
            SettingsAction.ChooseDestination ->
                effectChannel.trySend(SettingsEffect.ChooseDestination)
            is SettingsAction.DestinationSelected ->
                saveDestination(
                    value = runCatching { DestinationTree(action.uri) }.getOrNull(),
                    availabilityOnSuccess = DestinationAvailability.AVAILABLE,
                )
            SettingsAction.DestinationCancelled -> Unit
            SettingsAction.DestinationPermissionUnavailable -> setFieldError(
                SettingField.DESTINATION,
                "Не удалось сохранить доступ к выбранной папке.",
            )
            is SettingsAction.DestinationAvailabilityChecked ->
                applyDestinationAvailability(action)
            SettingsAction.ClearDestination -> saveDestination(
                value = null,
                availabilityOnSuccess = DestinationAvailability.NONE,
            )
            is SettingsAction.RevokeTrustedBrowser -> revokeTrusted(action.browserId)
            SettingsAction.RevokeAllTrustedBrowsers -> revokeAllTrusted()
            is SettingsAction.AutoAcceptToggled -> toggleAutoAccept(action.enabled)
            is SettingsAction.IdleStopSelected -> selectIdleStop(action.value)
        }
    }


    private fun saveDeviceName() {
        val draft = mutableUiState.value.deviceNameInput
        updateField(
            field = SettingField.DEVICE_NAME,
            submittedDraft = draft,
            operation = { updateDeviceName(draft) },
        )
    }

    private fun saveRetention() {
        val draft = mutableUiState.value.retentionInput
        val value = draft.toIntOrNull()
        if (value == null || value !in 1..365) {
            setFieldError(SettingField.RETENTION, "Введите число от 1 до 365.")
            return
        }
        updateField(
            field = SettingField.RETENTION,
            submittedDraft = draft,
            operation = { updateRetentionDays(value) },
        )
    }

    private fun saveFileLimit() {
        val draft = mutableUiState.value.fileLimitMiBInput
        val mebibytes = draft.toLongOrNull()
        val bytes = mebibytes?.let {
            runCatching { Math.multiplyExact(it, BYTES_PER_MIB) }.getOrNull()
        }
        if (bytes == null || bytes !in 1..HARD_MAX_FILE_BYTES) {
            setFieldError(SettingField.FILE_LIMIT, "Введите размер от 1 до 1024 МиБ.")
            return
        }
        updateField(
            field = SettingField.FILE_LIMIT,
            submittedDraft = draft,
            operation = { updateFileLimit(bytes) },
        )
    }

    private fun saveDestination(
        value: DestinationTree?,
        availabilityOnSuccess: DestinationAvailability,
    ) {
        updateField(
            field = SettingField.DESTINATION,
            availabilityOnSuccess = availabilityOnSuccess,
            operation = { updateDestinationTree(value) },
        )
    }

    private fun toggleAutoAccept(enabled: Boolean) {
        val state = mutableUiState.value
        if (
            enabled &&
            (state.settings.destinationTree == null ||
                state.destinationAvailability == DestinationAvailability.UNAVAILABLE)
        ) {
            setFieldError(
                SettingField.AUTO_ACCEPT,
                SettingsValidationError.AUTO_ACCEPT_DESTINATION.message(),
            )
            return
        }
        updateField(
            field = SettingField.AUTO_ACCEPT,
            operation = { updateAutoAcceptTrustedFiles(enabled) },
        )
    }

    private fun selectIdleStop(value: ru.hznik.devicebridge.domain.settings.IdleStopTimeout) {
        val state = mutableUiState.value
        if (state.idleStopState.isSaving || state.settings.idleStopTimeout == value) return
        updateField(
            field = SettingField.IDLE_STOP,
            operation = { updateIdleStopTimeout(value) },
        )
    }

    private fun applyDestinationAvailability(
        action: SettingsAction.DestinationAvailabilityChecked,
    ) {
        mutableUiState.update { current ->
            if (current.settings.destinationTree?.value != action.uri) {
                current
            } else {
                current.copy(
                    destinationAvailability = if (action.isAvailable) {
                        DestinationAvailability.AVAILABLE
                    } else {
                        DestinationAvailability.UNAVAILABLE
                    },
                    destinationState = current.destinationState.copy(
                        errorMessage = if (action.isAvailable) {
                            null
                        } else {
                            "Сохранённая папка недоступна. Выберите папку снова."
                        },
                    ),
                )
            }
        }
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
        submittedDraft: String? = null,
        availabilityOnSuccess: DestinationAvailability? = null,
        operation: suspend () -> SettingsUpdateResult,
    ) {
        updateFieldState(field) { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = runCatching { operation() }.getOrNull()) {
                is SettingsUpdateResult.Updated -> applySuccessfulFieldUpdate(
                    field = field,
                    settings = result.settings,
                    submittedDraft = submittedDraft,
                    availabilityOnSuccess = availabilityOnSuccess,
                )
                is SettingsUpdateResult.Invalid ->
                    setFieldError(field, result.reason.message())
                null -> setFieldError(field, "Не удалось сохранить настройку.")
            }
        }
    }

    private fun applySuccessfulFieldUpdate(
        field: SettingField,
        settings: ru.hznik.devicebridge.domain.settings.DeviceSettings,
        submittedDraft: String?,
        availabilityOnSuccess: DestinationAvailability?,
    ) {
        mutableUiState.update { current ->
            val currentDraft = when (field) {
                SettingField.DEVICE_NAME -> current.deviceNameInput
                SettingField.RETENTION -> current.retentionInput
                SettingField.FILE_LIMIT -> current.fileLimitMiBInput
                SettingField.DESTINATION, SettingField.AUTO_ACCEPT, SettingField.IDLE_STOP -> null
            }
            val hasNewerDraft = submittedDraft != null && currentDraft != submittedDraft
            val base = current.copy(
                settings = settings,
                destinationAvailability =
                    availabilityOnSuccess ?: current.destinationAvailability,
            )
            when (field) {
                SettingField.DEVICE_NAME -> base.copy(
                    deviceNameInput = if (hasNewerDraft) {
                        current.deviceNameInput
                    } else {
                        settings.deviceName
                    },
                    deviceNameState = SettingsFieldState(isDirty = hasNewerDraft),
                )
                SettingField.RETENTION -> base.copy(
                    retentionInput = if (hasNewerDraft) {
                        current.retentionInput
                    } else {
                        settings.retentionDays.toString()
                    },
                    retentionState = SettingsFieldState(isDirty = hasNewerDraft),
                )
                SettingField.FILE_LIMIT -> base.copy(
                    fileLimitMiBInput = if (hasNewerDraft) {
                        current.fileLimitMiBInput
                    } else {
                        (settings.effectiveFileLimitBytes / BYTES_PER_MIB).toString()
                    },
                    fileLimitState = SettingsFieldState(isDirty = hasNewerDraft),
                )
                SettingField.DESTINATION -> base.copy(
                    destinationState = SettingsFieldState(),
                )
                SettingField.AUTO_ACCEPT -> base.copy(
                    autoAcceptState = SettingsFieldState(),
                )
                SettingField.IDLE_STOP -> base.copy(
                    idleStopState = SettingsFieldState(),
                )
            }
        }
    }

    private fun setFieldError(field: SettingField, message: String) {
        updateFieldState(field) {
            it.copy(isSaving = false, errorMessage = message)
        }
    }

    private fun updateFieldState(
        field: SettingField,
        transform: (SettingsFieldState) -> SettingsFieldState,
    ) {
        mutableUiState.update { current ->
            when (field) {
                SettingField.DEVICE_NAME ->
                    current.copy(deviceNameState = transform(current.deviceNameState))
                SettingField.RETENTION ->
                    current.copy(retentionState = transform(current.retentionState))
                SettingField.DESTINATION ->
                    current.copy(destinationState = transform(current.destinationState))
                SettingField.FILE_LIMIT ->
                    current.copy(fileLimitState = transform(current.fileLimitState))
                SettingField.AUTO_ACCEPT ->
                    current.copy(autoAcceptState = transform(current.autoAcceptState))
                SettingField.IDLE_STOP ->
                    current.copy(idleStopState = transform(current.idleStopState))
            }
        }
    }
}

private enum class SettingField {
    DEVICE_NAME,
    RETENTION,
    DESTINATION,
    FILE_LIMIT,
    AUTO_ACCEPT,
    IDLE_STOP,
}

private fun SettingsValidationError.message(): String = when (this) {
    SettingsValidationError.DEVICE_NAME -> "Введите имя длиной от 1 до 40 символов."
    SettingsValidationError.RETENTION_DAYS -> "Введите число от 1 до 365."
    SettingsValidationError.FILE_LIMIT -> "Введите размер от 1 до 1024 МиБ."
    SettingsValidationError.AUTO_ACCEPT_DESTINATION -> "Сначала выберите папку для входящих файлов."
    SettingsValidationError.IDLE_STOP_TIMEOUT -> "Выберите время автоостановки из списка."
}
