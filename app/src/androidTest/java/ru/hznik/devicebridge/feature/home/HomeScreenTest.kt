package ru.hznik.devicebridge.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stoppedServerShowsHonestStateAndDisabledTransferActions() {
        composeRule.setContent {
            DeviceBridgeTheme {
                HomeScreen(uiState = ServerSessionUiState())
            }
        }

        composeRule.onNodeWithText("Сервер остановлен").assertIsDisplayed()
        composeRule.onNodeWithText("Текст").assertIsNotEnabled()
        composeRule.onNodeWithText("Файлы").assertIsNotEnabled()
        composeRule.onNodeWithText(
            "Сначала запустите сервер, затем подключите браузер.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Адрес", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Код подключения", substring = true).assertDoesNotExist()
    }
}
