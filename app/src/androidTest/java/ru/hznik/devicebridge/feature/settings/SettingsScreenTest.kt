package ru.hznik.devicebridge.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.hznik.devicebridge.domain.settings.DestinationTree
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.settings.IdleStopTimeout
import ru.hznik.devicebridge.domain.settings.ThemePreference
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId

class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun partialUploadsShowCountAndSizeAndOfferToDeleteThem() {
        val actions = mutableListOf<SettingsAction>()
        var state by mutableStateOf(SettingsUiState(loadState = SettingsLoadState.CONTENT))
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(uiState = state, onAction = { actions += it })
            }
        }

        scrollToTag("partial-uploads-summary").assertTextContains("Нет.", substring = true)
        composeRule.onNodeWithContentDescription("Удалить незавершённые файлы").assertDoesNotExist()

        state = state.copy(
            partialUploads = ru.hznik.devicebridge.data.file.PartialUploadSummary(
                count = 3,
                totalBytes = 30L * 1024 * 1024,
            ),
        )
        scrollToTag("partial-uploads-summary").assertTextContains("3 файла", substring = true)
        composeRule.onNodeWithContentDescription("Удалить незавершённые файлы")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf<SettingsAction>(SettingsAction.DiscardPartialUploads), actions)

        state = state.copy(discardPartialUploadsPending = true)
        composeRule.onNodeWithContentDescription("Удалить незавершённые файлы")
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun autoAcceptToggleReflectsDestinationAndDispatchesAction() {
        val actions = mutableListOf<SettingsAction>()
        val tree = DestinationTree("content://documents/tree/devicebridge")
        var state by mutableStateOf(
            SettingsUiState(loadState = SettingsLoadState.CONTENT),
        )
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(uiState = state, onAction = { actions += it })
            }
        }

        scrollToTag("auto-accept-toggle").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Сначала выберите папку для входящих файлов.").assertIsDisplayed()

        state = state.copy(
            settings = state.settings.copy(destinationTree = tree),
            destinationAvailability = DestinationAvailability.AVAILABLE,
        )
        scrollToTag("auto-accept-toggle").performScrollTo().assertIsEnabled().assertIsOff()
        composeRule.onNodeWithTag("auto-accept-toggle").performClick()
        assertEquals(listOf<SettingsAction>(SettingsAction.AutoAcceptToggled(true)), actions)

        state = state.copy(
            settings = state.settings.copy(autoAcceptTrustedFiles = true),
            destinationAvailability = DestinationAvailability.UNAVAILABLE,
        )
        scrollToTag("auto-accept-toggle").performScrollTo().assertIsOn()
        composeRule.onNodeWithText("Приостановлено: папка недоступна. Выберите папку снова.")
            .assertIsDisplayed()
    }

    @Test
    fun idleStopSelectorShowsDefaultAndDispatchesChoice() {
        val actions = mutableListOf<SettingsAction>()
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    uiState = SettingsUiState(loadState = SettingsLoadState.CONTENT),
                    onAction = { actions += it },
                )
            }
        }

        composeRule.onNodeWithText("Автоостановка без подключений").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("idle-stop-30").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("idle-stop-off").performScrollTo().assertIsNotSelected()
        // Instrumentation runs a debuggable build, so the one-minute E2E option is offered too.
        composeRule.onNodeWithTag("idle-stop-debug_1").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("idle-stop-off").performClick()
        assertEquals(
            listOf<SettingsAction>(SettingsAction.IdleStopSelected(IdleStopTimeout.OFF)),
            actions,
        )
    }

    @Test
    fun serverCardOffersTheTileForThisAndroidVersion() {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    uiState = SettingsUiState(loadState = SettingsLoadState.CONTENT),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag("add-server-tile").performScrollTo()
        composeRule.onNodeWithText("Плитка в быстрых настройках").assertIsDisplayed()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            composeRule.onNodeWithContentDescription("Добавить плитку DeviceBridge в быстрые настройки")
                .performScrollTo()
                .assertIsEnabled()
        } else {
            composeRule.onNodeWithText("Откройте шторку", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun exposesSettingsControlsAndDispatchesIndependentActions() {
        val actions = mutableListOf<SettingsAction>()
        var state by mutableStateOf(
            SettingsUiState(
                settings = DeviceSettings.defaults().copy(
                    destinationTree = DestinationTree("content://documents/tree/devicebridge"),
                ),
                loadState = SettingsLoadState.CONTENT,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    uiState = state,
                    onAction = { action ->
                        actions += action
                        if (action is SettingsAction.DeviceNameChanged) {
                            state = state.copy(deviceNameInput = action.value)
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithText("Имя телефона").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Поле имени телефона")
            .performTextReplacement("Мой телефон")
        composeRule.onNodeWithContentDescription("Сохранить имя телефона").performClick()
        scrollToText("Срок хранения истории").assertIsDisplayed()
        scrollToText("Максимальный размер файла").assertIsDisplayed()
        scrollToText("Папка для входящих файлов").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Выбрать папку для входящих файлов")
            .performScrollTo()
            .performClick()
        scrollToText("Доверенные браузеры").assertIsDisplayed()

        assertEquals(
            listOf(
                SettingsAction.DeviceNameChanged("Мой телефон"),
                SettingsAction.SaveDeviceName,
                SettingsAction.ChooseDestination,
            ),
            actions,
        )
    }

    @Test
    fun saveButtonShowsRepositoryConfirmedValueAndReturnsToSaveAfterEditing() {
        var state by mutableStateOf(
            SettingsUiState(
                settings = DeviceSettings.defaults(),
                loadState = SettingsLoadState.CONTENT,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    uiState = state,
                    onAction = { action ->
                        state = when (action) {
                            is SettingsAction.DeviceNameChanged ->
                                state.copy(deviceNameInput = action.value)
                            SettingsAction.SaveDeviceName ->
                                state.copy(
                                    settings = state.settings.copy(
                                        deviceName = state.deviceNameInput.trim(),
                                    ),
                                )
                            else -> state
                        }
                    },
                )
            }
        }

        val save = composeRule.onNodeWithContentDescription("Сохранить имя телефона")
        save.assertTextEquals("Сохранено")
        composeRule.onNodeWithContentDescription("Поле имени телефона")
            .performTextReplacement("Pixel Test")
        save.assertTextEquals("Сохранить")
        save.performClick()
        save.assertTextEquals("Сохранено")
        assertEquals("Pixel Test", state.settings.deviceName)
    }

    @Test
    fun remainsReadableWithLargeFontScaleAndShowsFieldError() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    SettingsScreen(
                        uiState = SettingsUiState(
                            loadState = SettingsLoadState.CONTENT,
                            retentionState = SettingsFieldState(
                                errorMessage = "Введите число от 1 до 365.",
                            ),
                        ),
                        onAction = { },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Настройки").assertIsDisplayed()
        scrollToText("Введите число от 1 до 365.").assertIsDisplayed()
        scrollToText("Доверенных браузеров пока нет").assertIsDisplayed()
    }

    @Test
    fun trustedBrowserMetadataHasAccessibleRevokeControlsWithoutSecret() {
        val actions = mutableListOf<SettingsAction>()
        val browserId = TrustedBrowserId("trusted-edge")
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    uiState = SettingsUiState(
                        loadState = SettingsLoadState.CONTENT,
                        trustedBrowsers = listOf(
                            TrustedBrowserUiState(
                                id = browserId,
                                browserLabel = "Edge на Windows",
                                lastUsedAtEpochMillis = 1_500,
                                expiresAtEpochMillis = 2_000,
                            ),
                        ),
                    ),
                    onAction = actions::add,
                )
            }
        }

        scrollToText("Edge на Windows").assertIsDisplayed()
        composeRule.onNodeWithText("trusted-edge", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Отозвать доступ Edge на Windows")
            .performScrollTo()
            .performClick()

        assertEquals(listOf(SettingsAction.RevokeTrustedBrowser(browserId)), actions)
    }

    @Test
    fun darkThemeSwitchReflectsChoiceAndDispatchesOppositeTheme() {
        val actions = mutableListOf<SettingsAction>()
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    uiState = SettingsUiState(
                        loadState = SettingsLoadState.CONTENT,
                        themePreference = ThemePreference.DARK,
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("dark-theme-toggle")
            .assertIsDisplayed()
            .assertIsOn()
            .performClick()

        assertEquals(listOf(SettingsAction.ThemeSelected(ThemePreference.LIGHT)), actions)
    }

    @Test
    fun readFailureShowsRetryInsteadOfDefaultSettings() {
        val actions = mutableListOf<SettingsAction>()
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    uiState = SettingsUiState(
                        loadState = SettingsLoadState.ERROR,
                        loadErrorMessage = "Хранилище недоступно.",
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("Хранилище недоступно.").assertIsDisplayed()
        composeRule.onNodeWithText("Имя телефона").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Повторить загрузку настроек")
            .performClick()

        assertEquals(listOf(SettingsAction.RetryLoad), actions)
    }

    @Test
    fun revokedDestinationShowsChooseAgainAction() {
        val actions = mutableListOf<SettingsAction>()
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    uiState = SettingsUiState(
                        settings = DeviceSettings.defaults().copy(
                            destinationTree = DestinationTree(
                                "content://documents/tree/revoked",
                            ),
                        ),
                        loadState = SettingsLoadState.CONTENT,
                        destinationAvailability = DestinationAvailability.UNAVAILABLE,
                        destinationState = SettingsFieldState(
                            errorMessage = "Сохранённая папка недоступна.",
                        ),
                    ),
                    onAction = actions::add,
                )
            }
        }
        scrollToText("Выбрать снова").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Выбрать папку для входящих файлов")
            .performScrollTo()
            .performClick()

        assertEquals(listOf(SettingsAction.ChooseDestination), actions)
    }

    @Test
    fun failedSaveKeepsDraftAndShowsInlineRetryableFeedback() {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    uiState = SettingsUiState(
                        settings = DeviceSettings.defaults(),
                        loadState = SettingsLoadState.CONTENT,
                        deviceNameInput = "Черновик Pixel",
                        deviceNameState = SettingsFieldState(
                            errorMessage = "Не удалось сохранить имя. Повторите попытку.",
                            isDirty = true,
                        ),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Поле имени телефона")
            .assertTextContains("Черновик Pixel")
        composeRule.onNodeWithText("Не удалось сохранить имя. Повторите попытку.")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Сохранить имя телефона")
            .assertTextEquals("Сохранить")
            .assertIsEnabled()
    }

    @Test
    fun tappingBlankAreaClearsSettingsFocusAndKeepsInput() {
        var state by mutableStateOf(
            SettingsUiState(
                settings = DeviceSettings.defaults(),
                loadState = SettingsLoadState.CONTENT,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    uiState = state,
                    onAction = { action ->
                        if (action is SettingsAction.DeviceNameChanged) {
                            state = state.copy(deviceNameInput = action.value)
                        }
                    },
                )
            }
        }

        val field = composeRule.onNodeWithContentDescription("Поле имени телефона")
        field.performClick()
        field.performTextReplacement("Pixel сохранён")
        field.assertIsFocused()

        composeRule.onNodeWithTag("settings-list")
            .performTouchInput { click(Offset(4f, 4f)) }

        field.assertIsNotFocused().assertTextContains("Pixel сохранён")
    }

    @Test
    fun decorativeSettingsCopyIsOmittedButPrimaryHeadingRemains() {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    uiState = SettingsUiState(
                        settings = DeviceSettings.defaults(),
                        loadState = SettingsLoadState.CONTENT,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Настройки").assertIsDisplayed()
        composeRule.onNodeWithText("Локальные параметры").assertDoesNotExist()
        composeRule.onNodeWithText(
            "Все параметры хранятся только на этом телефоне.",
        ).assertDoesNotExist()
    }

    // Settings is a lazy list: cards below the fold are not composed until scrolled to.
    private fun scrollToTag(tag: String): SemanticsNodeInteraction {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
        return composeRule.onNodeWithTag(tag)
    }

    private fun scrollToText(text: String): SemanticsNodeInteraction {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        return composeRule.onNodeWithText(text)
    }
}
