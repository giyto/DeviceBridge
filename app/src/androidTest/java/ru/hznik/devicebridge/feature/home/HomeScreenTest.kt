package ru.hznik.devicebridge.feature.home

import android.content.ClipboardManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.PairingRequestId

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lifecycleStatesShowHonestCardsAndBlockConflictingCommands() {
        var state by mutableStateOf(
            ServerSessionUiState(status = HomeServerStatus.Starting),
        )
        composeRule.setContent {
            DeviceBridgeTheme { HomeScreen(uiState = state) }
        }
        composeRule.onNodeWithText("Сервер запускается").assertIsDisplayed()
        composeRule.onNodeWithText("Запуск…").assertIsNotEnabled()

        composeRule.runOnIdle {
            state = ServerSessionUiState(status = HomeServerStatus.Stopping)
        }
        composeRule.onNodeWithText("Сервер останавливается").assertIsDisplayed()
        composeRule.onNodeWithText("Остановка…").assertIsNotEnabled()

        composeRule.runOnIdle {
            state = ServerSessionUiState(
                status = HomeServerStatus.Error,
                errorMessage = "Соединение с локальной сетью потеряно.",
            )
        }
        composeRule.onNodeWithText("Нужен повторный запуск").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить запуск").assertIsEnabled()
        composeRule.onNodeWithText("Адрес сервера", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun idleStopIsShownAsAPlainStopWithItsReason() {
        setScreen(ServerSessionUiState(idleStoppedAfterMinutes = 30))

        composeRule.onNodeWithText("Сервер остановлен").assertIsDisplayed()
        composeRule.onNodeWithText("Остановлен автоматически: 30 минут без подключений.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Нужен повторный запуск").assertDoesNotExist()
        composeRule.onNodeWithText("Запустить сервер").performScrollTo().assertIsEnabled()
    }

    @Test
    fun lifecycleFailureShowsOnlyItsApplicableRecoveryAction() {
        var received: HomeAction? = null
        val permissionFailure = ru.hznik.devicebridge.domain.error.UserFacingFailure(
            code = ru.hznik.devicebridge.domain.error.FailureCode.LOCAL_NETWORK_PERMISSION_DENIED,
            severity = ru.hznik.devicebridge.domain.error.FailureSeverity.RECOVERABLE,
            recoveryActions = setOf(
                ru.hznik.devicebridge.domain.error.RecoveryAction.REQUEST_PERMISSION,
            ),
        )
        var state by mutableStateOf(
            ServerSessionUiState(
                status = HomeServerStatus.Error,
                errorMessage = "Нет разрешения на доступ к локальной сети.",
                failure = permissionFailure,
            ),
        )
        composeRule.setContent {
            DeviceBridgeTheme {
                HomeScreen(uiState = state, onAction = { received = it })
            }
        }

        composeRule.onNodeWithText("Запросить разрешение")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(HomeAction.RequestPermissionClicked, received)
            received = null
            state = state.copy(
                failure = ru.hznik.devicebridge.domain.error.UserFacingFailure(
                    code = ru.hznik.devicebridge.domain.error.FailureCode.LOCAL_NETWORK_PERMISSION_REVOKED,
                    severity = ru.hznik.devicebridge.domain.error.FailureSeverity.RECOVERABLE,
                    recoveryActions = setOf(
                        ru.hznik.devicebridge.domain.error.RecoveryAction.OPEN_SETTINGS,
                    ),
                ),
            )
        }
        composeRule.onNodeWithText("Открыть настройки")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(HomeAction.OpenSettingsClicked, received)
            received = null
            state = state.copy(
                failure = ru.hznik.devicebridge.domain.error.UserFacingFailure(
                    code = ru.hznik.devicebridge.domain.error.FailureCode.NETWORK_LOST,
                    severity = ru.hznik.devicebridge.domain.error.FailureSeverity.RECOVERABLE,
                    recoveryActions = setOf(
                        ru.hznik.devicebridge.domain.error.RecoveryAction.CONNECT_TO_LOCAL_NETWORK,
                    ),
                ),
            )
        }
        composeRule.onNodeWithText("Запустить снова")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(HomeAction.StartAgainClicked, received)
        }
    }

    @Test
    fun secureModeKeepsThePlainAddressAndExplainsTheSwitchToHttps() {
        var state by mutableStateOf(
            ServerSessionUiState(
                status = HomeServerStatus.Running,
                localAddress = "http://192.168.1.24:8787",
                secureMode = false,
            ),
        )
        composeRule.setContent {
            DeviceBridgeTheme {
                HomeScreen(uiState = state)
            }
        }

        composeRule.onNodeWithTag("secure-mode-address-hint").assertDoesNotExist()
        composeRule.runOnIdle { state = state.copy(secureMode = true) }
        composeRule.onNodeWithText("http://192.168.1.24:8787").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("secure-mode-address-hint").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun unusableCertificateLeadsToTheHttpsSettings() {
        var openedSettings = 0
        var received: HomeAction? = null
        composeRule.setContent {
            DeviceBridgeTheme {
                HomeScreen(
                    uiState = ServerSessionUiState(
                        status = HomeServerStatus.Error,
                        errorMessage = "Сертификат защищённого режима недоступен. Сбросьте его в настройках.",
                        failure = ru.hznik.devicebridge.domain.error.UserFacingFailure(
                            code = ru.hznik.devicebridge.domain.error.FailureCode.SECURE_CERTIFICATE_UNAVAILABLE,
                            severity = ru.hznik.devicebridge.domain.error.FailureSeverity.RECOVERABLE,
                            recoveryActions = setOf(
                                ru.hznik.devicebridge.domain.error.RecoveryAction.RESET_CERTIFICATE,
                            ),
                        ),
                    ),
                    onAction = { received = it },
                    onOpenSettings = { openedSettings += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Сертификат защищённого режима недоступен.", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Открыть настройки HTTPS")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, openedSettings)
            assertEquals(null, received)
        }
    }

    @Test
    fun runningShowsOnlyActualAddressLiveUptimeAndCopiesWithAccessibleAction() {
        val address = "http://192.168.1.24:49321"
        val clipboard = RecordingClipboard()
        setScreen(
            ServerSessionUiState(
                status = HomeServerStatus.Running,
                localAddress = address,
                uptimeSeconds = 3_661,
            ),
            clipboard = clipboard,
        )

        composeRule.onNodeWithText("Сервер запущен").assertIsDisplayed()
        composeRule.onNodeWithText(address).assertIsDisplayed()
        composeRule.onNodeWithText("01:01:01").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Скопировать адрес DeviceBridge")
            .assertIsDisplayed()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            clipboard.clipEntry
                ?.clipData
                ?.getItemAt(0)
                ?.text
                ?.toString() == address
        }
        assertEquals(
            address,
            clipboard.clipEntry?.clipData?.getItemAt(0)?.text?.toString(),
        )
    }

    @Test
    fun stoppedAndRunningWithoutPairingKeepTransfersDisabledWithExplanation() {
        var state by mutableStateOf(ServerSessionUiState())
        composeRule.setContent {
            DeviceBridgeTheme { HomeScreen(uiState = state) }
        }
        composeRule.onNodeWithText("Сервер остановлен").assertIsDisplayed()
        composeRule.onNodeWithText("Запустить сервер").assertIsEnabled()
        composeRule.onNodeWithText("Текст").assertIsNotEnabled()
        composeRule.onNodeWithText("Файлы").assertIsNotEnabled()

        composeRule.runOnIdle {
            state = ServerSessionUiState(
                status = HomeServerStatus.Running,
                localAddress = "http://192.168.1.24:8787",
            )
        }
        composeRule.onNodeWithText("Текст").assertIsNotEnabled()
        composeRule.onNodeWithText("Файлы").assertIsNotEnabled()
        composeRule.onNodeWithText(
            "Сначала безопасно подключите браузер по коду выше.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun oneOrSeveralActiveSessionsEnableTextAndFileTransfer() {
        var state by mutableStateOf(
            ServerSessionUiState(
                status = HomeServerStatus.Running,
                activeBrowsers = listOf(
                    ActiveBrowserUiState(
                        BrowserSessionId("session-1"),
                        "Яндекс Браузер",
                        "192.168.1.2",
                    ),
                ),
            ),
        )
        composeRule.setContent {
            DeviceBridgeTheme { HomeScreen(uiState = state) }
        }

        composeRule.onNodeWithText("Текст").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Файлы").performScrollTo().assertIsEnabled()

        composeRule.runOnIdle {
            state = state.copy(
                activeBrowsers = state.activeBrowsers + ActiveBrowserUiState(
                    BrowserSessionId("session-2"),
                    "Edge",
                    "192.168.1.3",
                ),
            )
        }

        composeRule.onNodeWithText("Текст").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Файлы").performScrollTo().assertIsEnabled()
    }

    @Test
    fun activeTextTransferShowsOnlyGenericStatus() {
        setScreen(
            ServerSessionUiState(
                status = HomeServerStatus.Running,
                textTransferStatus = HomeTextTransferStatus.Active,
            ),
        )

        composeRule.onNodeWithText("Передача текста выполняется")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun runningShowsPairingCodeAndCountdownOnlyForActiveGeneration() {
        var state by mutableStateOf(
            ServerSessionUiState(
                status = HomeServerStatus.Running,
                localAddress = "http://192.168.1.24:8787",
                pairingCode = "123456",
                pairingExpiresInSeconds = 95,
            ),
        )
        composeRule.setContent {
            DeviceBridgeTheme { HomeScreen(uiState = state) }
        }

        composeRule.onNodeWithText("Код подключения").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("123456").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Код обновится через 01:35")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.runOnIdle { state = ServerSessionUiState() }
        composeRule.onNodeWithText("123456").assertDoesNotExist()
    }

    @Test
    fun newPendingRequestIsScrolledIntoView() {
        val running = ServerSessionUiState(
            status = HomeServerStatus.Running,
            localAddress = "http://192.168.1.24:8787",
            pairingCode = "123456",
            pairingExpiresInSeconds = 120,
        )
        var state by mutableStateOf(running)
        composeRule.setContent {
            DeviceBridgeTheme {
                HomeScreen(uiState = state)
            }
        }

        state = running.copy(
            pendingBrowsers = listOf(
                PendingBrowserUiState(PairingRequestId("edge-request"), "Edge", "192.168.1.2", 42),
            ),
        )

        composeRule.onNodeWithContentDescription("Разрешить Edge с адреса 192.168.1.2")
            .assertIsDisplayed()
    }

    @Test
    fun pendingBrowserCardsDispatchExactApproveAndDenyIds() {
        val actions = mutableListOf<HomeAction>()
        val edge = PairingRequestId("edge-request")
        val chrome = PairingRequestId("chrome-request")
        setScreen(
            ServerSessionUiState(
                status = HomeServerStatus.Running,
                pairingCode = "123456",
                pairingExpiresInSeconds = 120,
                pendingBrowsers = listOf(
                    PendingBrowserUiState(edge, "Edge", "192.168.1.2", 42),
                    PendingBrowserUiState(chrome, "Chrome", "192.168.1.3", 38),
                ),
            ),
            onAction = actions::add,
        )

        composeRule.onAllNodesWithText("Разрешить").assertCountEquals(2)
        composeRule.onNodeWithContentDescription(
            "Разрешить Edge с адреса 192.168.1.2",
        ).performScrollTo().performClick()
        composeRule.onNodeWithContentDescription(
            "Отклонить Chrome с адреса 192.168.1.3",
        ).performScrollTo().performClick()

        assertEquals(
            listOf(HomeAction.ApproveBrowser(edge), HomeAction.DenyBrowser(chrome)),
            actions,
        )
    }

    @Test
    fun trustRequestExplainsPersistenceAndDispatchesExplicitRememberDecision() {
        val actions = mutableListOf<HomeAction>()
        val requestId = PairingRequestId("remember-request")
        setScreen(
            ServerSessionUiState(
                status = HomeServerStatus.Running,
                pairingCode = "123456",
                pairingExpiresInSeconds = 120,
                pendingBrowsers = listOf(
                    PendingBrowserUiState(
                        id = requestId,
                        browserLabel = "Яндекс Браузер",
                        sourceIpv4 = "192.168.1.4",
                        expiresInSeconds = 42,
                        rememberBrowserRequested = true,
                    ),
                ),
            ),
            onAction = actions::add,
        )

        composeRule.onNodeWithText(
            "Браузер просит сохранить доступ на этом устройстве на срок до 30 дней.",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Разрешить и запомнить Яндекс Браузер с адреса 192.168.1.4",
        ).performScrollTo().performClick()

        assertEquals(listOf(HomeAction.ApproveAndRememberBrowser(requestId)), actions)
    }

    @Test
    fun activeBrowsersCanBeRevokedWithoutShowingCredentials() {
        val actions = mutableListOf<HomeAction>()
        val sessionId = BrowserSessionId("session-1")
        setScreen(
            ServerSessionUiState(
                status = HomeServerStatus.Running,
                pairingCode = "123456",
                pairingExpiresInSeconds = 120,
                activeBrowsers = listOf(
                    ActiveBrowserUiState(sessionId, "Chrome", "192.168.1.3"),
                ),
            ),
            onAction = actions::add,
        )

        composeRule.onNodeWithText("Подключённые браузеры: 1")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Отключить Chrome с адреса 192.168.1.3",
        ).performScrollTo().performClick()
        composeRule.onNodeWithText("Браузер подключён. Передача текста и ссылок доступна.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Bearer", substring = true, ignoreCase = true)
            .assertDoesNotExist()
        assertEquals(listOf(HomeAction.RevokeBrowser(sessionId)), actions)
    }

    @Test
    fun offlineBrowserIsMarkedNotCountedAndStillRevocable() {
        val actions = mutableListOf<HomeAction>()
        val onlineId = BrowserSessionId("session-1")
        val offlineId = BrowserSessionId("session-2")
        setScreen(
            ServerSessionUiState(
                status = HomeServerStatus.Running,
                pairingCode = "123456",
                pairingExpiresInSeconds = 120,
                activeBrowsers = listOf(
                    ActiveBrowserUiState(onlineId, "Chrome", "192.168.1.3"),
                    ActiveBrowserUiState(offlineId, "Edge", "192.168.1.5", connected = false),
                ),
            ),
            onAction = actions::add,
        )

        composeRule.onNodeWithText("Подключённые браузеры: 1")
            .performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Не в сети", useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Edge, 192.168.1.5, не в сети")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Отключить Edge с адреса 192.168.1.5, не в сети",
        ).performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription("Отключить Chrome с адреса 192.168.1.3")
            .performScrollTo().assertIsEnabled()
        assertEquals(listOf(HomeAction.RevokeBrowser(offlineId)), actions)
    }

    @Test
    fun onlyOfflineBrowsersKeepPairingGuidanceAndDisableTransfers() {
        setScreen(
            ServerSessionUiState(
                status = HomeServerStatus.Running,
                pairingCode = "123456",
                pairingExpiresInSeconds = 120,
                activeBrowsers = listOf(
                    ActiveBrowserUiState(
                        BrowserSessionId("session-2"),
                        "Edge",
                        "192.168.1.5",
                        connected = false,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Подключённые браузеры: 0")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Сначала безопасно подключите браузер по коду выше.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun permissionAndNotificationMessagesHaveClearActions() {
        setScreen(
            ServerSessionUiState(
                isPermissionExplanationVisible = true,
                showNotificationWarning = true,
            ),
        )

        composeRule.onNodeWithText("Разрешите доступ к локальной сети")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Повторить запрос").assertIsEnabled()
        composeRule.onNodeWithText("Уведомления отключены").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun darkThemeWithLargeFontKeepsPrimaryInformationVisible() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2f),
            ) {
                DeviceBridgeTheme(darkTheme = true) {
                    HomeScreen(
                        uiState = ServerSessionUiState(
                            status = HomeServerStatus.Running,
                            localAddress = "http://192.168.1.24:8787",
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithText("DeviceBridge").assertIsDisplayed()
        composeRule.onNodeWithText("Сервер запущен").assertIsDisplayed()
        composeRule.onNodeWithText("http://192.168.1.24:8787")
            .assertTextContains("192.168.1.24", substring = true)
    }

    @Test
    fun connectionGuideCoversTrustedWifiPairingAndTogglesFromItsHeader() {
        var state by mutableStateOf(ServerSessionUiState())
        composeRule.setContent {
            DeviceBridgeTheme {
                HomeScreen(uiState = state)
            }
        }

        // Collapsed on a fresh install until asked for.
        composeRule.onNodeWithText(
            "Подключите телефон и компьютер к одной доверенной Wi-Fi сети.",
        ).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Показать инструкцию подключения")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText(
            "Подключите телефон и компьютер к одной доверенной Wi-Fi сети.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Аккаунт и отдельная программа для компьютера не нужны.",
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Скрыть инструкцию подключения")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText(
            "Подключите телефон и компьютер к одной доверенной Wi-Fi сети.",
        ).assertDoesNotExist()
        composeRule.onNodeWithText("Скрыть инструкцию").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Показать инструкцию подключения")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText(
            "Подключите телефон и компьютер к одной доверенной Wi-Fi сети.",
        ).assertIsDisplayed()

        composeRule.runOnIdle {
            state = ServerSessionUiState(
                status = HomeServerStatus.Running,
                localAddress = "http://192.168.1.24:8787",
                pairingCode = "123456",
                pairingExpiresInSeconds = 120,
            )
        }
        composeRule.onNodeWithText("Откройте адрес на компьютере")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "Введите код 123456 и подтвердите браузер на телефоне.",
        ).performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle {
            state = state.copy(
                activeBrowsers = listOf(
                    ActiveBrowserUiState(
                        BrowserSessionId("session-guide"),
                        "Edge",
                        "192.168.1.2",
                    ),
                ),
            )
        }
        composeRule.onNodeWithContentDescription("Скрыть инструкцию подключения")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Откройте адрес на компьютере")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun openedConnectionGuideSurvivesSavedStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            DeviceBridgeTheme {
                HomeScreen(uiState = ServerSessionUiState())
            }
        }

        composeRule.onNodeWithContentDescription("Показать инструкцию подключения")
            .performClick()
        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithContentDescription("Скрыть инструкцию подключения")
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "Подключите телефон и компьютер к одной доверенной Wi-Fi сети.",
        ).assertIsDisplayed()
    }

    @Test
    fun connectionGuideStartsCollapsedWhenTheServerIsRunning() {
        setScreen(
            ServerSessionUiState(
                status = HomeServerStatus.Running,
                localAddress = "http://192.168.1.24:8787",
                pairingCode = "123456",
                pairingExpiresInSeconds = 120,
            ),
        )

        composeRule.onNodeWithContentDescription("Показать инструкцию подключения")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Откройте адрес на компьютере").assertDoesNotExist()
    }

    @Test
    fun connectionPairingAndBrowserTransitionsArePoliteLiveRegions() {
        setScreen(
            ServerSessionUiState(
                status = HomeServerStatus.Running,
                pairingCode = "123456",
                pairingExpiresInSeconds = 120,
                pendingBrowsers = listOf(
                    PendingBrowserUiState(
                        PairingRequestId("live-request"),
                        "Firefox",
                        "192.168.1.5",
                        42,
                    ),
                ),
            ),
        )
        val polite = SemanticsMatcher.expectValue(
            SemanticsProperties.LiveRegion,
            LiveRegionMode.Polite,
        )

        composeRule.onNodeWithContentDescription("Состояние подключения: Сервер запущен")
            .assert(polite)
        composeRule.onNodeWithContentDescription("Код подключения 123456")
            .performScrollTo()
            .assert(polite)
        composeRule.onNodeWithContentDescription(
            "Новый запрос на подключение: Firefox, 192.168.1.5",
        ).performScrollTo().assert(polite)
    }
    @Test
    fun startCommandIsNotRepeatedAfterRecompositionOrRestoration() {
        val actions = mutableListOf<HomeAction>()
        var state by mutableStateOf(ServerSessionUiState())
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            DeviceBridgeTheme {
                HomeScreen(uiState = state, onAction = actions::add)
            }
        }

        composeRule.onNodeWithText("Запустить сервер")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            state = state.copy(commandPending = true)
        }
        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertEquals(listOf(HomeAction.StartClicked), actions)
        }
    }
    private fun setScreen(
        state: ServerSessionUiState,
        clipboard: Clipboard? = null,
        onAction: (HomeAction) -> Unit = {},
    ) {
        composeRule.setContent {
            DeviceBridgeTheme {
                if (clipboard == null) {
                    HomeScreen(uiState = state, onAction = onAction)
                } else {
                    CompositionLocalProvider(LocalClipboard provides clipboard) {
                        HomeScreen(uiState = state, onAction = onAction)
                    }
                }
            }
        }
    }

    private class RecordingClipboard : Clipboard {
        @Volatile
        var clipEntry: ClipEntry? = null

        override suspend fun getClipEntry(): ClipEntry? = clipEntry

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            this.clipEntry = clipEntry
        }

        override val nativeClipboard: ClipboardManager
            get() = error("Native clipboard is not used by this test")
    }

    @Test
    fun decorativeLocalityCopyIsOmittedButPrimaryHeadingRemains() {
        setScreen(ServerSessionUiState())

        composeRule.onNodeWithText("DeviceBridge").assertIsDisplayed()
        composeRule.onNodeWithText("Локальная связь").assertDoesNotExist()
        composeRule.onNodeWithText(
            "Телефон и компьютер - рядом, без облака и внешнего сервера.",
        ).assertDoesNotExist()
    }
}
