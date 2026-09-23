package ru.hznik.devicebridge.feature.settings

import ru.hznik.devicebridge.domain.repository.ThemePreferenceRepository
import ru.hznik.devicebridge.domain.settings.ThemePreference
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.hznik.devicebridge.domain.file.HARD_MAX_FILE_BYTES
import ru.hznik.devicebridge.domain.repository.SettingsRepository
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository
import ru.hznik.devicebridge.domain.repository.TrustedBrowserRepository
import ru.hznik.devicebridge.domain.settings.DestinationTree
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.settings.SettingsUpdateResult
import ru.hznik.devicebridge.domain.settings.SettingsValidationError
import ru.hznik.devicebridge.domain.usecase.ObserveSettingsUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateAutoAcceptTrustedFilesUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateDestinationTreeUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateDeviceNameUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateFileLimitUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateRetentionDaysUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveTrustedBrowsersUseCase
import ru.hznik.devicebridge.domain.usecase.RevokeAllTrustedBrowsersUseCase
import ru.hznik.devicebridge.domain.usecase.RevokeTrustedBrowserUseCase
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.trust.IssuedTrustedBrowser
import ru.hznik.devicebridge.domain.trust.TrustedBrowser
import ru.hznik.devicebridge.domain.trust.TrustedBrowserAuthenticationResult
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId
import ru.hznik.devicebridge.domain.trust.TrustedBrowserIssueRequest

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun repositoryStateSurvivesViewModelRecreation() = runTest(dispatcher) {
        val repository = FakeSettingsRepository()
        val first = viewModel(repository)
        runCurrent()

        first.onAction(SettingsAction.DeviceNameChanged("Pixel 8"))
        first.onAction(SettingsAction.SaveDeviceName)
        runCurrent()

        val recreated = viewModel(repository)
        runCurrent()
        assertEquals("Pixel 8", recreated.uiState.value.settings.deviceName)
        assertEquals("Pixel 8", recreated.uiState.value.deviceNameInput)
    }
    @Test
    fun themeSelectionIsShownImmediatelyAndPersisted() = runTest(dispatcher) {
        val themeRepository = FakeThemePreferenceRepository()
        val viewModel = viewModel(FakeSettingsRepository(), themeRepository = themeRepository)
        runCurrent()
        assertNull(viewModel.uiState.value.themePreference)

        viewModel.onAction(SettingsAction.ThemeSelected(ThemePreference.DARK))
        assertEquals(ThemePreference.DARK, viewModel.uiState.value.themePreference)
        runCurrent()

        assertEquals(ThemePreference.DARK, themeRepository.stored.value)
    }

    @Test
    fun settingsReadFailureIsNotShownAsDefaultsAndCanBeRetried() = runTest(dispatcher) {
        val repository = FakeSettingsRepository().apply { failReads = true }
        val viewModel = viewModel(repository)
        assertEquals(SettingsLoadState.LOADING, viewModel.uiState.value.loadState)

        runCurrent()

        assertEquals(SettingsLoadState.ERROR, viewModel.uiState.value.loadState)
        assertTrue(requireNotNull(viewModel.uiState.value.loadErrorMessage).isNotBlank())

        repository.failReads = false
        repository.current.value = repository.current.value.copy(deviceName = "Pixel 9")
        viewModel.onAction(SettingsAction.RetryLoad)
        runCurrent()

        assertEquals(SettingsLoadState.CONTENT, viewModel.uiState.value.loadState)
        assertEquals("Pixel 9", viewModel.uiState.value.settings.deviceName)
    }

    @Test
    fun failedSaveKeepsPersistedValueAndDraftThenRetriesWithoutDuplicateWrite() =
        runTest(dispatcher) {
            val repository = FakeSettingsRepository().apply { failDeviceNameWrites = 1 }
            val viewModel = viewModel(repository)
            runCurrent()

            viewModel.onAction(SettingsAction.DeviceNameChanged("  Pixel draft  "))
            viewModel.onAction(SettingsAction.SaveDeviceName)
            runCurrent()

            assertEquals(
                DeviceSettings.defaults().deviceName,
                viewModel.uiState.value.settings.deviceName,
            )
            assertEquals("  Pixel draft  ", viewModel.uiState.value.deviceNameInput)
            assertTrue(viewModel.uiState.value.deviceNameState.isDirty)
            assertTrue(
                requireNotNull(viewModel.uiState.value.deviceNameState.errorMessage).isNotBlank(),
            )

            viewModel.onAction(SettingsAction.SaveDeviceName)
            runCurrent()

            assertEquals("Pixel draft", viewModel.uiState.value.settings.deviceName)
            assertEquals("Pixel draft", viewModel.uiState.value.deviceNameInput)
            assertFalse(viewModel.uiState.value.deviceNameState.isDirty)
            assertNull(viewModel.uiState.value.deviceNameState.errorMessage)
            assertEquals(2, repository.deviceNameWriteAttempts)
        }

    @Test
    fun revokedDestinationIsMarkedUnavailableWithoutClearingOtherState() =
        runTest(dispatcher) {
            val repository = FakeSettingsRepository().apply {
                current.value = current.value.copy(
                    deviceName = "My phone",
                    destinationTree = DestinationTree("content://provider/tree/original"),
                )
            }
            val trusted = FakeTrustedBrowserRepository()
            val viewModel = viewModel(repository, trusted)
            runCurrent()

            viewModel.onAction(
                SettingsAction.DestinationAvailabilityChecked(
                    uri = "content://provider/tree/original",
                    isAvailable = false,
                ),
            )
            viewModel.onAction(SettingsAction.DestinationCancelled)

            assertEquals(
                DestinationAvailability.UNAVAILABLE,
                viewModel.uiState.value.destinationAvailability,
            )
            assertEquals("My phone", viewModel.uiState.value.settings.deviceName)
            assertEquals(
                "content://provider/tree/original",
                viewModel.uiState.value.settings.destinationTree?.value,
            )
            assertEquals(1, viewModel.uiState.value.trustedBrowsers.size)

            viewModel.onAction(
                SettingsAction.DestinationSelected("content://provider/tree/replacement"),
            )
            runCurrent()

            assertEquals(
                DestinationAvailability.AVAILABLE,
                viewModel.uiState.value.destinationAvailability,
            )
            assertEquals(
                "content://provider/tree/replacement",
                viewModel.uiState.value.settings.destinationTree?.value,
            )
        }

    @Test
    fun eachFieldHasIndependentSavingAndErrorState() = runTest(dispatcher) {
        val repository = FakeSettingsRepository()
        val viewModel = viewModel(repository)
        runCurrent()

        viewModel.onAction(SettingsAction.RetentionChanged("0"))
        viewModel.onAction(SettingsAction.SaveRetention)
        runCurrent()

        assertEquals(
            "Введите число от 1 до 365.",
            viewModel.uiState.value.retentionState.errorMessage,
        )
        assertNull(viewModel.uiState.value.deviceNameState.errorMessage)
        assertFalse(viewModel.uiState.value.retentionState.isSaving)

        viewModel.onAction(SettingsAction.FileLimitMiBChanged("512"))
        viewModel.onAction(SettingsAction.SaveFileLimit)
        runCurrent()
        assertEquals(512L * 1024 * 1024, repository.current.value.effectiveFileLimitBytes)
        assertNull(viewModel.uiState.value.fileLimitState.errorMessage)
    }

    @Test
    fun unavailablePickerPermissionKeepsPreviousDestination() = runTest(dispatcher) {
        val repository = FakeSettingsRepository().apply {
            current.value = current.value.copy(
                destinationTree = DestinationTree("content://provider/tree/original"),
            )
        }
        val viewModel = viewModel(repository)
        runCurrent()

        viewModel.onAction(SettingsAction.DestinationPermissionUnavailable)

        assertEquals(
            "content://provider/tree/original",
            repository.current.value.destinationTree?.value,
        )
        assertEquals(
            "Не удалось сохранить доступ к выбранной папке.",
            viewModel.uiState.value.destinationState.errorMessage,
        )
    }

    @Test
    fun trustedBrowserIsRemovedOnlyAfterSuccessfulRepositoryResult() = runTest(dispatcher) {
        val trusted = FakeTrustedBrowserRepository()
        val sessions = FakeBrowserSessionRepository(trusted)
        val viewModel = viewModel(
            repository = FakeSettingsRepository(),
            trustedRepository = trusted,
            sessionRepository = sessions,
        )
        runCurrent()
        val browserId = TrustedBrowserId("trusted-1")
        assertEquals(listOf(browserId), viewModel.uiState.value.trustedBrowsers.map { it.id })

        viewModel.onAction(SettingsAction.RevokeTrustedBrowser(browserId))
        assertEquals(listOf(browserId), viewModel.uiState.value.trustedBrowsers.map { it.id })
        assertTrue(browserId in viewModel.uiState.value.revokingTrustedBrowserIds)
        runCurrent()

        assertEquals(listOf(browserId), sessions.revokedTrusted)
        assertTrue(viewModel.uiState.value.trustedBrowsers.isEmpty())
        assertTrue(viewModel.uiState.value.revokingTrustedBrowserIds.isEmpty())
    }

    @Test
    fun destinationPickerEffectIsDeliveredOnceWithoutReplay() = runTest(dispatcher) {
        val viewModel = viewModel(FakeSettingsRepository())
        runCurrent()
        val firstEffect = async { viewModel.effects.first() }
        runCurrent()

        viewModel.onAction(SettingsAction.ChooseDestination)

        assertEquals(SettingsEffect.ChooseDestination, firstEffect.await())
        assertNull(
            withTimeoutOrNull(100) {
                viewModel.effects.first()
            },
        )
    }

    @Test
    fun autoAcceptCannotBeEnabledWithoutDestination() = runTest(dispatcher) {
        val repository = FakeSettingsRepository()
        val viewModel = viewModel(repository)
        runCurrent()

        assertEquals(AutoAcceptStatus.NO_DESTINATION, viewModel.uiState.value.autoAcceptStatus)
        viewModel.onAction(SettingsAction.AutoAcceptToggled(true))
        runCurrent()

        assertFalse(repository.current.value.autoAcceptTrustedFiles)
        assertEquals(0, repository.autoAcceptWrites)
        assertTrue(requireNotNull(viewModel.uiState.value.autoAcceptState.errorMessage).isNotBlank())
    }

    @Test
    fun autoAcceptTogglesWhenDestinationIsAvailable() = runTest(dispatcher) {
        val repository = FakeSettingsRepository()
        repository.current.value = repository.current.value.copy(destinationTree = TEST_TREE)
        val viewModel = viewModel(repository)
        runCurrent()
        viewModel.onAction(SettingsAction.DestinationAvailabilityChecked(TEST_TREE.value, true))

        assertEquals(AutoAcceptStatus.OFF, viewModel.uiState.value.autoAcceptStatus)
        viewModel.onAction(SettingsAction.AutoAcceptToggled(true))
        runCurrent()
        assertTrue(repository.current.value.autoAcceptTrustedFiles)
        assertEquals(AutoAcceptStatus.ON, viewModel.uiState.value.autoAcceptStatus)

        viewModel.onAction(SettingsAction.AutoAcceptToggled(false))
        runCurrent()
        assertFalse(repository.current.value.autoAcceptTrustedFiles)
        assertEquals(AutoAcceptStatus.OFF, viewModel.uiState.value.autoAcceptStatus)
    }

    @Test
    fun enabledAutoAcceptIsPausedWhileDestinationIsUnavailable() = runTest(dispatcher) {
        val repository = FakeSettingsRepository()
        repository.current.value = repository.current.value.copy(
            destinationTree = TEST_TREE,
            autoAcceptTrustedFiles = true,
        )
        val viewModel = viewModel(repository)
        runCurrent()

        viewModel.onAction(SettingsAction.DestinationAvailabilityChecked(TEST_TREE.value, false))
        assertEquals(AutoAcceptStatus.PAUSED, viewModel.uiState.value.autoAcceptStatus)
        assertTrue(repository.current.value.autoAcceptTrustedFiles)

        viewModel.onAction(SettingsAction.AutoAcceptToggled(true))
        runCurrent()
        assertEquals(0, repository.autoAcceptWrites)

        viewModel.onAction(SettingsAction.DestinationAvailabilityChecked(TEST_TREE.value, true))
        assertEquals(AutoAcceptStatus.ON, viewModel.uiState.value.autoAcceptStatus)
    }

    @Test
    fun idleStopSelectionIsSavedOnceAndReflectedInState() = runTest(dispatcher) {
        val repository = FakeSettingsRepository()
        val viewModel = viewModel(repository)
        runCurrent()

        assertEquals(
            ru.hznik.devicebridge.domain.settings.IdleStopTimeout.MIN_30,
            viewModel.uiState.value.settings.idleStopTimeout,
        )
        viewModel.onAction(
            SettingsAction.IdleStopSelected(ru.hznik.devicebridge.domain.settings.IdleStopTimeout.MIN_30),
        )
        runCurrent()
        assertEquals(0, repository.idleStopWrites)

        viewModel.onAction(
            SettingsAction.IdleStopSelected(ru.hznik.devicebridge.domain.settings.IdleStopTimeout.OFF),
        )
        runCurrent()
        assertEquals(1, repository.idleStopWrites)
        assertEquals(
            ru.hznik.devicebridge.domain.settings.IdleStopTimeout.OFF,
            viewModel.uiState.value.settings.idleStopTimeout,
        )
        assertFalse(viewModel.uiState.value.idleStopState.isSaving)
    }

    private fun viewModel(
        repository: SettingsRepository,
        trustedRepository: FakeTrustedBrowserRepository = FakeTrustedBrowserRepository(),
        sessionRepository: BrowserSessionRepository = FakeBrowserSessionRepository(trustedRepository),
        themeRepository: ThemePreferenceRepository = FakeThemePreferenceRepository(),
    ) = SettingsViewModel(
        observeSettings = ObserveSettingsUseCase(repository),
        updateDeviceName = UpdateDeviceNameUseCase(repository),
        updateRetentionDays = UpdateRetentionDaysUseCase(repository),
        updateDestinationTree = UpdateDestinationTreeUseCase(repository),
        updateFileLimit = UpdateFileLimitUseCase(repository),
        updateAutoAcceptTrustedFiles = UpdateAutoAcceptTrustedFilesUseCase(repository),
        updateIdleStopTimeout = ru.hznik.devicebridge.domain.usecase.UpdateIdleStopTimeoutUseCase(repository),
        observeTrustedBrowsers = ObserveTrustedBrowsersUseCase(trustedRepository),
        revokeTrustedBrowser = RevokeTrustedBrowserUseCase(sessionRepository),
        revokeAllTrustedBrowsers = RevokeAllTrustedBrowsersUseCase(sessionRepository),
        themePreferenceRepository = themeRepository,
    )

    private class FakeThemePreferenceRepository : ThemePreferenceRepository {
        val stored = MutableStateFlow<ThemePreference?>(null)
        override val themePreference: Flow<ThemePreference?> = stored
        override suspend fun updateThemePreference(value: ThemePreference) {
            stored.value = value
        }
    }

    private class FakeTrustedBrowserRepository : TrustedBrowserRepository {
        val browsers = MutableStateFlow(
            listOf(
                TrustedBrowser(
                    id = TrustedBrowserId("trusted-1"),
                    browserLabel = "Edge",
                    createdAtEpochMillis = 1_000,
                    lastUsedAtEpochMillis = 1_500,
                    expiresAtEpochMillis = 2_000,
                ),
            ),
        )
        override val trustedBrowsers: Flow<List<TrustedBrowser>> = browsers
        override suspend fun issue(request: TrustedBrowserIssueRequest): IssuedTrustedBrowser =
            error("Not used")
        override suspend fun authenticate(
            rawCredential: String,
            nowEpochMillis: Long,
        ): TrustedBrowserAuthenticationResult = TrustedBrowserAuthenticationResult.Invalid
        override suspend fun revoke(browserId: TrustedBrowserId): Boolean {
            val removed = browsers.value.any { it.id == browserId }
            browsers.value = browsers.value.filterNot { it.id == browserId }
            return removed
        }
        override suspend fun revokeAll(): Int {
            val count = browsers.value.size
            browsers.value = emptyList()
            return count
        }
        override suspend fun deleteExpired(nowEpochMillis: Long): Int = 0
    }

    private class FakeBrowserSessionRepository(
        private val trusted: FakeTrustedBrowserRepository,
    ) : BrowserSessionRepository {
        override val state: StateFlow<BrowserSessionState> =
            MutableStateFlow(BrowserSessionState.inactive())
        val revokedTrusted = mutableListOf<TrustedBrowserId>()
        override suspend fun approve(requestId: PairingRequestId) = Unit
        override suspend fun deny(requestId: PairingRequestId) = Unit
        override suspend fun revoke(sessionId: BrowserSessionId) = Unit
        override suspend fun revokeTrustedBrowser(browserId: TrustedBrowserId): Boolean {
            revokedTrusted += browserId
            return trusted.revoke(browserId)
        }
        override suspend fun revokeAllTrustedBrowsers(): Int = trusted.revokeAll()
    }

    private class FakeSettingsRepository : SettingsRepository {
        val current = MutableStateFlow(DeviceSettings.defaults())
        var failReads = false
        var failDeviceNameWrites = 0
        var deviceNameWriteAttempts = 0
        var autoAcceptWrites = 0
        var idleStopWrites = 0
        override val settings: Flow<DeviceSettings> = flow {
            if (failReads) throw java.io.IOException("settings unavailable")
            emitAll(current)
        }

        override suspend fun updateDeviceName(value: String): SettingsUpdateResult {
            deviceNameWriteAttempts += 1
            if (failDeviceNameWrites > 0) {
                failDeviceNameWrites -= 1
                throw java.io.IOException("write unavailable")
            }
            val normalized = value.trim()
            if (normalized.isBlank() || normalized.codePointCount(0, normalized.length) > 40) {
                return SettingsUpdateResult.Invalid(SettingsValidationError.DEVICE_NAME)
            }
            current.value = current.value.copy(deviceName = normalized)
            return SettingsUpdateResult.Updated(current.value)
        }

        override suspend fun updateRetentionDays(value: Int): SettingsUpdateResult {
            if (value !in 1..365) {
                return SettingsUpdateResult.Invalid(SettingsValidationError.RETENTION_DAYS)
            }
            current.value = current.value.copy(retentionDays = value)
            return SettingsUpdateResult.Updated(current.value)
        }

        override suspend fun updateDestinationTree(
            value: DestinationTree?,
        ): SettingsUpdateResult {
            current.value = current.value.copy(destinationTree = value)
            return SettingsUpdateResult.Updated(current.value)
        }

        override suspend fun updateEffectiveFileLimitBytes(value: Long): SettingsUpdateResult {
            if (value !in 1..HARD_MAX_FILE_BYTES) {
                return SettingsUpdateResult.Invalid(SettingsValidationError.FILE_LIMIT)
            }
            current.value = current.value.copy(effectiveFileLimitBytes = value)
            return SettingsUpdateResult.Updated(current.value)
        }

        override suspend fun updateAutoAcceptTrustedFiles(enabled: Boolean): SettingsUpdateResult {
            autoAcceptWrites += 1
            if (enabled && current.value.destinationTree == null) {
                return SettingsUpdateResult.Invalid(SettingsValidationError.AUTO_ACCEPT_DESTINATION)
            }
            current.value = current.value.copy(autoAcceptTrustedFiles = enabled)
            return SettingsUpdateResult.Updated(current.value)
        }

        override suspend fun updateIdleStopTimeout(
            value: ru.hznik.devicebridge.domain.settings.IdleStopTimeout,
        ): SettingsUpdateResult {
            idleStopWrites += 1
            current.value = current.value.copy(idleStopTimeout = value)
            return SettingsUpdateResult.Updated(current.value)
        }
    }

    private companion object {
        val TEST_TREE = DestinationTree("content://documents/tree/devicebridge")
    }
}
