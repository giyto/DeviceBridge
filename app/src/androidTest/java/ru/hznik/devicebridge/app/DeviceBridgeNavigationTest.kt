package ru.hznik.devicebridge.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme
import ru.hznik.devicebridge.feature.home.HomeScreen
import ru.hznik.devicebridge.feature.home.ActiveBrowserUiState
import ru.hznik.devicebridge.feature.home.HomeServerStatus
import ru.hznik.devicebridge.feature.home.ServerSessionUiState
import ru.hznik.devicebridge.feature.text.TextScreen
import ru.hznik.devicebridge.feature.text.TextUiState
import ru.hznik.devicebridge.domain.session.BrowserSessionId

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

    @Test
    fun textQuickActionOpensFlowAndExplicitBackReturnsHome() {
        setAppContent(withActiveSession = true)

        composeRule.onNodeWithText("Текст").performScrollTo().performClick()

        composeRule.onNodeWithText("Текст и ссылки").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Вернуться на главный экран")
            .performClick()
        composeRule.onNodeWithText("DeviceBridge").assertIsDisplayed()
    }

    @Test
    fun restoredTextDestinationNeedsOnlyOneBackToReachHome() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            DeviceBridgeTheme {
                testApp(withActiveSession = true)
            }
        }
        composeRule.onNodeWithText("Текст").performScrollTo().performClick()
        composeRule.onNodeWithText("Текст и ссылки").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Текст и ссылки").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Вернуться на главный экран")
            .performClick()
        composeRule.onNodeWithText("DeviceBridge").assertIsDisplayed()
    }

    private fun setAppContent(withActiveSession: Boolean = false) {
        composeRule.setContent {
            DeviceBridgeTheme {
                testApp(withActiveSession)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun testApp(withActiveSession: Boolean) {
        val sessionState = if (withActiveSession) {
            ServerSessionUiState(
                status = HomeServerStatus.Running,
                activeBrowsers = listOf(
                    ActiveBrowserUiState(
                        id = BrowserSessionId("navigation-session"),
                        browserLabel = "Яндекс Браузер",
                        sourceIpv4 = "192.168.1.2",
                    ),
                ),
            )
        } else {
            ServerSessionUiState()
        }
        DeviceBridgeApp(
            homeContent = { onOpenText ->
                HomeScreen(
                    uiState = sessionState,
                    onOpenText = onOpenText,
                )
            },
            textContent = { onBack, _ ->
                TextScreen(
                    uiState = TextUiState(),
                    onBack = onBack,
                )
            },
        )
    }
}
