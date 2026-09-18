package ru.hznik.devicebridge.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.hznik.devicebridge.domain.settings.DestinationTree
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId

class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exposesSettingsControlsAndDispatchesIndependentActions() {
        val actions = mutableListOf<SettingsAction>()
        var state by mutableStateOf(
            SettingsUiState(
                settings = DeviceSettings.defaults().copy(
                    destinationTree = DestinationTree("content://documents/tree/devicebridge"),
                ),
                isLoading = false,
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
        composeRule.onNodeWithText("Срок хранения истории").assertIsDisplayed()
        composeRule.onNodeWithText("Максимальный размер файла").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Поле имени телефона")
            .performTextReplacement("Мой телефон")
        composeRule.onNodeWithContentDescription("Сохранить имя телефона").performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToIndex(3)
        composeRule.onNodeWithText("Папка для входящих файлов").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Выбрать папку для входящих файлов")
            .performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToIndex(4)
        composeRule.onNodeWithText("Доверенные браузеры").assertIsDisplayed()

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
                isLoading = false,
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
                            isLoading = false,
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
        composeRule.onNodeWithText("Введите число от 1 до 365.").assertIsDisplayed()
        composeRule.onNodeWithText("Доверенных браузеров пока нет").assertIsDisplayed()
    }

    @Test
    fun trustedBrowserMetadataHasAccessibleRevokeControlsWithoutSecret() {
        val actions = mutableListOf<SettingsAction>()
        val browserId = TrustedBrowserId("trusted-edge")
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    uiState = SettingsUiState(
                        isLoading = false,
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

        composeRule.onNode(hasScrollAction()).performScrollToIndex(4)
        composeRule.onNodeWithText("Edge на Windows").assertIsDisplayed()
        composeRule.onNodeWithText("trusted-edge", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Отозвать доступ Edge на Windows")
            .performClick()

        assertEquals(listOf(SettingsAction.RevokeTrustedBrowser(browserId)), actions)
    }
}
