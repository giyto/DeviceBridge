package ru.hznik.devicebridge.feature.settings

import ru.hznik.devicebridge.domain.repository.ThemePreferenceRepository
import ru.hznik.devicebridge.domain.settings.ThemePreference
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
import ru.hznik.devicebridge.data.tls.LocalCertificateAuthority
import ru.hznik.devicebridge.data.tls.RootCertificateStatus
import ru.hznik.devicebridge.data.tls.SecureModeController
import ru.hznik.devicebridge.data.tls.SoftwareTlsKeyStore
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.model.ServerStopReason
import ru.hznik.devicebridge.domain.repository.ServerLifecycleRepository
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
    fun partialUploadsAreSummarizedAndDiscardedOnceOnRequest() = runTest(dispatcher) {
        val store = FakePartialUploadStore(count = 2, totalBytes = 30L * 1024 * 1024)
        val viewModel = viewModel(FakeSettingsRepository(), partialUploads = store)
        runCurrent()
        assertEquals(2, viewModel.uiState.value.partialUploads.count)
        assertEquals(30L * 1024 * 1024, viewModel.uiState.value.partialUploads.totalBytes)

        viewModel.onAction(SettingsAction.DiscardPartialUploads)
        assertTrue(viewModel.uiState.value.discardPartialUploadsPending)
        viewModel.onAction(SettingsAction.DiscardPartialUploads)
        runCurrent()

        assertEquals(1, store.discards)
        assertEquals(0, viewModel.uiState.value.partialUploads.count)
        assertFalse(viewModel.uiState.value.discardPartialUploadsPending)
        viewModel.onAction(SettingsAction.DiscardPartialUploads)
        runCurrent()
        assertEquals(1, store.discards)

        val failing = FakePartialUploadStore(count = 1, totalBytes = 10, failing = true)
        val failingViewModel = viewModel(FakeSettingsRepository(), partialUploads = failing)
        runCurrent()
        failingViewModel.onAction(SettingsAction.DiscardPartialUploads)
        runCurrent()
        assertEquals(
            "Не удалось удалить незавершённые файлы.",
            failingViewModel.uiState.value.partialUploadsError,
        )
        assertEquals(1, failingViewModel.uiState.value.partialUploads.count)
    }

    @Test
    fun partialUploadCountUsesRussianPluralForms() {
        assertEquals(
            listOf("1 файл", "2 файла", "5 файлов", "11 файлов", "21 файл", "104 файла"),
            listOf(1, 2, 5, 11, 21, 104).map(::partialUploadCountLabel),
        )
    }

    @Test
    fun networkNameIsSavedInLowerCaseAndWaitsForARunningServerToRestart() = runTest(dispatcher) {
        val lifecycle = FakeLifecycle().apply { state.value = FakeLifecycle.RUNNING }
        val viewModel = viewModel(FakeSettingsRepository(), lifecycle = lifecycle)
        runCurrent()
        assertEquals("devicebridge", viewModel.uiState.value.networkNameInput)

        viewModel.onAction(SettingsAction.NetworkNameChanged("Nikita"))
        assertTrue(viewModel.uiState.value.networkNameState.isDirty)
        viewModel.onAction(SettingsAction.SaveNetworkName)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals("nikita", state.settings.networkName.value)
        assertEquals("nikita", state.networkNameInput)
        assertFalse(state.networkNameState.isDirty)
        assertTrue(state.networkNameAppliesAfterRestart)
        assertTrue(lifecycle.commands.isEmpty())
    }

    @Test
    fun networkNameSavedWhileStoppedNeedsNoRestartHint() = runTest(dispatcher) {
        val viewModel = viewModel(FakeSettingsRepository())
        runCurrent()

        viewModel.onAction(SettingsAction.NetworkNameChanged("nikita"))
        viewModel.onAction(SettingsAction.SaveNetworkName)
        runCurrent()

        assertFalse(viewModel.uiState.value.networkNameAppliesAfterRestart)
    }

    @Test
    fun invalidNetworkNameExplainsTheRulesAndKeepsTheOldName() = runTest(dispatcher) {
        val viewModel = viewModel(FakeSettingsRepository())
        runCurrent()

        viewModel.onAction(SettingsAction.NetworkNameChanged("мой телефон"))
        viewModel.onAction(SettingsAction.SaveNetworkName)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals("devicebridge", state.settings.networkName.value)
        assertTrue(state.networkNameState.errorMessage!!.contains("латинские буквы"))
        assertEquals("мой телефон", state.networkNameInput)
    }

    @Test
    fun rootFromBeforeCustomNamesIsFlaggedForTheChosenName() = runTest(dispatcher) {
        val keys = SoftwareTlsKeyStore()
        val directory = java.nio.file.Files.createTempDirectory("settings-legacy-tls").toFile()
        val publicKey = keys.generate(LocalCertificateAuthority.ROOT_ALIAS, ru.hznik.devicebridge.data.tls.TlsKeyPurpose.CERTIFICATE_AUTHORITY)
        val now = java.time.Instant.now()
        val legacy = ru.hznik.devicebridge.data.tls.X509Profiles.root(
            publicKey = publicKey,
            serial = byteArrayOf(0x41, 0x03),
            commonName = "DeviceBridge Local CA old",
            notBefore = now,
            notAfter = now.plusSeconds(3_600),
            sign = { keys.signSha256WithEcdsa(LocalCertificateAuthority.ROOT_ALIAS, it) },
            permittedDnsName = ru.hznik.devicebridge.data.tls.DEVICEBRIDGE_LOCAL_NAME,
        )
        java.io.File(directory, "root.der").writeBytes(legacy.encoded)
        val viewModel = viewModel(FakeSettingsRepository(), authority = LocalCertificateAuthority(keys, directory))
        runCurrent()
        assertTrue(viewModel.uiState.value.certificateCoversNetworkName)

        viewModel.onAction(SettingsAction.NetworkNameChanged("nikita"))
        viewModel.onAction(SettingsAction.SaveNetworkName)
        runCurrent()

        assertFalse(viewModel.uiState.value.certificateCoversNetworkName)
    }

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
    fun secureModeTurnsOnAtOnceWhileStoppedAndShowsTheFingerprint() = runTest(dispatcher) {
        val lifecycle = FakeLifecycle()
        val viewModel = viewModel(FakeSettingsRepository(), lifecycle = lifecycle)
        runCurrent()
        assertEquals(RootCertificateStatus.NotCreated, viewModel.uiState.value.rootCertificate)

        viewModel.onAction(SettingsAction.SecureModeToggled(true))
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.settings.secureModeEnabled)
        assertNull(state.pendingSecureModeChange)
        assertTrue(state.rootCertificate is RootCertificateStatus.Ready)
        assertEquals(emptyList<String>(), lifecycle.commands)
    }

    @Test
    fun secureModeAsksBeforeRestartingARunningServer() = runTest(dispatcher) {
        val lifecycle = FakeLifecycle().apply { state.value = FakeLifecycle.RUNNING }
        val viewModel = viewModel(FakeSettingsRepository(), lifecycle = lifecycle)
        runCurrent()

        viewModel.onAction(SettingsAction.SecureModeToggled(true))
        runCurrent()
        assertEquals(SecureModeChange.Toggle(true), viewModel.uiState.value.pendingSecureModeChange)
        assertFalse(viewModel.uiState.value.settings.secureModeEnabled)

        viewModel.onAction(SettingsAction.SecureModeChangeDismissed)
        runCurrent()
        assertNull(viewModel.uiState.value.pendingSecureModeChange)
        assertFalse(viewModel.uiState.value.settings.secureModeEnabled)

        viewModel.onAction(SettingsAction.SecureModeToggled(true))
        viewModel.onAction(SettingsAction.SecureModeChangeConfirmed)
        runCurrent()
        assertTrue(viewModel.uiState.value.settings.secureModeEnabled)
        assertEquals(listOf("stop", "start"), lifecycle.commands)
    }

    @Test
    fun certificateResetIsConfirmedAndLeavesANewFingerprintAndNotice() = runTest(dispatcher) {
        val viewModel = viewModel(FakeSettingsRepository())
        runCurrent()
        viewModel.onAction(SettingsAction.SecureModeToggled(true))
        runCurrent()
        val before = viewModel.uiState.value.rootCertificate as RootCertificateStatus.Ready

        viewModel.onAction(SettingsAction.ResetCertificateClicked)
        assertEquals(SecureModeChange.ResetCertificate, viewModel.uiState.value.pendingSecureModeChange)
        viewModel.onAction(SettingsAction.SecureModeChangeConfirmed)
        runCurrent()

        val after = viewModel.uiState.value.rootCertificate as RootCertificateStatus.Ready
        assertFalse(before.fingerprints == after.fingerprints)
        assertTrue(viewModel.uiState.value.certificateWasReset)
        viewModel.onAction(SettingsAction.CertificateResetNoticeDismissed)
        assertFalse(viewModel.uiState.value.certificateWasReset)
    }

    @Test
    fun sharingTheCertificateOpensTheShareSheetOrExplainsTheFailure() = runTest(dispatcher) {
        val viewModel = viewModel(FakeSettingsRepository())
        runCurrent()
        val effects = mutableListOf<SettingsEffect>()
        val collecting = backgroundScope.launch { viewModel.effects.collect { effects += it } }

        viewModel.onAction(SettingsAction.ShareCertificateClicked)
        runCurrent()

        assertEquals(listOf<SettingsEffect>(SettingsEffect.ShareCertificate(SHARED_CERTIFICATE_URI)), effects)
        assertNull(viewModel.uiState.value.certificateShareError)

        val failing = viewModel(
            FakeSettingsRepository(),
            certificateExporter = ru.hznik.devicebridge.data.tls.RootCertificateExporter { null },
        )
        runCurrent()
        failing.onAction(SettingsAction.ShareCertificateClicked)
        runCurrent()
        assertEquals("Не удалось подготовить файл сертификата.", failing.uiState.value.certificateShareError)
        collecting.cancel()
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
        partialUploads: ru.hznik.devicebridge.data.file.PartialUploadStore = FakePartialUploadStore(),
        lifecycle: FakeLifecycle = FakeLifecycle(),
        certificateExporter: ru.hznik.devicebridge.data.tls.RootCertificateExporter =
            ru.hznik.devicebridge.data.tls.RootCertificateExporter { SHARED_CERTIFICATE_URI },
        authority: LocalCertificateAuthority = LocalCertificateAuthority(
            SoftwareTlsKeyStore(),
            java.nio.file.Files.createTempDirectory("settings-tls").toFile(),
        ),
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
        partialUploads = partialUploads,
        secureMode = SecureModeController(
            updateSetting = repository::updateSecureMode,
            awaitSettingApplied = {},
            lifecycle = lifecycle,
            authority = authority,
            io = dispatcher,
        ),
        certificateExporter = certificateExporter,
        updateNetworkName = ru.hznik.devicebridge.domain.usecase.UpdateNetworkNameUseCase(repository),
    )

    class FakeLifecycle : ServerLifecycleRepository {
        override val state = MutableStateFlow<ServerLifecycleState>(ServerLifecycleState.Stopped)
        override val lastStopReason: StateFlow<ServerStopReason?> = MutableStateFlow(null)
        val commands = mutableListOf<String>()

        override suspend fun start() {
            commands += "start"
            state.value = RUNNING
        }

        override suspend fun stop(reason: ServerStopReason) {
            commands += "stop"
            state.value = ServerLifecycleState.Stopped
        }

        companion object {
            val RUNNING = ServerLifecycleState.Running(
                1,
                ru.hznik.devicebridge.domain.model.ServerEndpoint("192.168.1.24", 8_787),
                0,
            )
        }
    }

    private class FakePartialUploadStore(
        count: Int = 0,
        totalBytes: Long = 0,
        private val failing: Boolean = false,
    ) : ru.hznik.devicebridge.data.file.PartialUploadStore {
        val summaryState = MutableStateFlow(
            ru.hznik.devicebridge.data.file.PartialUploadSummary(count, totalBytes),
        )
        var discards = 0
        override val summary: Flow<ru.hznik.devicebridge.data.file.PartialUploadSummary> = summaryState
        override suspend fun find(key: ru.hznik.devicebridge.data.file.PartialUploadKey) = null
        override suspend fun save(record: ru.hznik.devicebridge.data.file.PartialUploadRecord) = Unit
        override suspend fun forget(documentUri: String) = Unit
        override suspend fun discard(documentUri: String) = Unit
        override suspend fun cleanup(nowEpochMillis: Long) = 0
        override suspend fun discardAll(): Int {
            discards += 1
            if (failing) error("provider unavailable")
            val count = summaryState.value.count
            summaryState.value = ru.hznik.devicebridge.data.file.PartialUploadSummary(0, 0)
            return count
        }
    }

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
        override val connectedSessionIds: StateFlow<Set<BrowserSessionId>> =
            MutableStateFlow(emptySet())
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

        override suspend fun updateSecureMode(enabled: Boolean): SettingsUpdateResult {
            current.value = current.value.copy(secureModeEnabled = enabled)
            return SettingsUpdateResult.Updated(current.value)
        }

        override suspend fun updateNetworkName(value: String): SettingsUpdateResult {
            val name = ru.hznik.devicebridge.domain.settings.NetworkName.parse(value)
                ?: return SettingsUpdateResult.Invalid(
                    ru.hznik.devicebridge.domain.settings.SettingsValidationError.NETWORK_NAME,
                )
            current.value = current.value.copy(networkName = name)
            return SettingsUpdateResult.Updated(current.value)
        }
    }

    private companion object {
        const val SHARED_CERTIFICATE_URI =
            "content://ru.hznik.devicebridge.certificates/shared_certificate/DeviceBridge-CA.crt"
        val TEST_TREE = DestinationTree("content://documents/tree/devicebridge")
    }
}
