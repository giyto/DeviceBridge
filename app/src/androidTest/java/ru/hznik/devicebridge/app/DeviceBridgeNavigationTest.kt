package ru.hznik.devicebridge.app

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
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme
import ru.hznik.devicebridge.feature.home.HomeScreen
import ru.hznik.devicebridge.feature.home.ServerSessionUiState

@RunWith(AndroidJUnit4::class)
class DeviceBridgeNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appStartsOnHome() {
        setAppContent()

        composeRule.onNodeWithText("DeviceBridge").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Раздел Главная").assertIsSelected()
    }

    @Test
    fun historyDestinationOpens() {
        setAppContent()

        composeRule.onNodeWithContentDescription("Раздел История").performClick()

        composeRule.onNodeWithText("История передач").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Раздел История").assertIsSelected()
    }

    @Test
    fun homeDestinationOpensAfterHistory() {
        setAppContent()
        composeRule.onNodeWithContentDescription("Раздел История").performClick()

        composeRule.onNodeWithContentDescription("Раздел Главная").performClick()

        composeRule.onNodeWithText("DeviceBridge").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Раздел Главная").assertIsSelected()
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
