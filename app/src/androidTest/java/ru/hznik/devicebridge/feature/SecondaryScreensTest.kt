package ru.hznik.devicebridge.feature

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.app.DeviceBridgeApp
import ru.hznik.devicebridge.feature.home.HomeScreen
import ru.hznik.devicebridge.feature.home.ServerSessionUiState
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme

@RunWith(AndroidJUnit4::class)
class SecondaryScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun historyShowsAnHonestEmptyState() {
        setAppContent()

        composeRule.onNodeWithContentDescription("Раздел История").performClick()

        composeRule.onNodeWithText("История передач").assertIsDisplayed()
        composeRule.onNodeWithText("История пока пуста").assertIsDisplayed()
        composeRule.onNodeWithText("Тестовая передача", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun settingsShowsOnlyAvailableFoundationInformation() {
        setAppContent()

        composeRule.onNodeWithContentDescription("Раздел Настройки").performClick()

        composeRule.onNodeWithContentDescription("Заголовок экрана Настройки")
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "Параметры подключения появятся вместе с локальным сервером.",
        ).assertIsDisplayed()
    }

    @Test
    fun navigationExposesReadableLabelsAndSelectionState() {
        setAppContent()

        composeRule.onNodeWithContentDescription("Раздел Главная")
            .assertIsDisplayed()
            .assertIsSelected()
        composeRule.onNodeWithContentDescription("Раздел История").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Раздел Настройки").assertIsDisplayed()
    }

    private fun setAppContent() {
        composeRule.setContent {
            DeviceBridgeTheme {
                DeviceBridgeApp(
                    homeContent = { HomeScreen(ServerSessionUiState()) },
                )
            }
        }
    }
}
