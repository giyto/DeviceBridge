package ru.hznik.devicebridge.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.domain.error.FailureCode
import ru.hznik.devicebridge.domain.error.FailureContext
import ru.hznik.devicebridge.domain.error.FailureSeverity
import ru.hznik.devicebridge.domain.error.RecoveryAction
import ru.hznik.devicebridge.domain.error.UserFacingFailure
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme

@RunWith(AndroidJUnit4::class)
class FailureTechnicalDetailsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun detailsExpandAndCopyOnlySanitizedText() {
        var copied = ""
        val failure = UserFacingFailure(
            code = FailureCode.NETWORK_LOST,
            severity = FailureSeverity.RECOVERABLE,
            recoveryActions = setOf(RecoveryAction.CONNECT_TO_LOCAL_NETWORK),
            context = FailureContext.fromUntrusted(
                mapOf(
                    "operationId" to "operation-42",
                    "sessionToken" to "bearer-secret",
                ),
            ),
        )
        composeRule.setContent {
            DeviceBridgeTheme {
                FailureTechnicalDetails(failure = failure, onCopy = { copied = it })
            }
        }

        composeRule.onNodeWithText("Технические сведения")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("Код: network_lost", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Скопировать сведения")
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertTrue(copied.contains("operationId: operation-42"))
            assertTrue(!copied.contains("bearer-secret"))
        }
    }
}
