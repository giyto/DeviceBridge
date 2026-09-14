package ru.hznik.devicebridge.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.diagnostics.server.ServerState
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme

@RunWith(AndroidJUnit4::class)
class DiagnosticsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stoppedStateOffersServerStart() {
        setScreen(ServerState.Stopped)

        composeRule.onNodeWithText("Сервер остановлен").assertIsDisplayed()
        composeRule.onNodeWithText("Запустить сервер")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun runningStateShowsAddressTokenAndLifecycleActions() {
        setScreen(
            ServerState.Running(
                address = "192.168.1.10",
                port = 8_787,
                token = "diagnostic-token",
            ),
        )

        composeRule.onNodeWithText("Сервер запущен").assertIsDisplayed()
        composeRule.onNodeWithText("http://192.168.1.10:8787").assertIsDisplayed()
        composeRule.onNodeWithText("diagnostic-token").assertIsDisplayed()
        composeRule.onNodeWithText("Остановить сервер").assertIsDisplayed()
        composeRule.onNodeWithText("Перезапустить").assertIsDisplayed()
    }

    @Test
    fun errorStateExplainsFailureAndOffersRetry() {
        setScreen(ServerState.Error("Port is busy"))

        composeRule.onNodeWithText("Ошибка запуска").assertIsDisplayed()
        composeRule.onNodeWithText("Port is busy").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить запуск")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    private fun setScreen(state: ServerState) {
        composeRule.setContent {
            DeviceBridgeTheme {
                DiagnosticsScreen(
                    uiModel = toDiagnosticsUiModel(state),
                    permissionError = null,
                    onPrimaryAction = {},
                    onRestart = {},
                )
            }
        }
    }
}
