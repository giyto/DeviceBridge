package ru.hznik.devicebridge.core.ui

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isHiddenFromAccessibility
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.hznik.devicebridge.domain.error.FailureCode
import ru.hznik.devicebridge.domain.error.FailureSeverity
import ru.hznik.devicebridge.domain.error.RecoveryAction
import ru.hznik.devicebridge.domain.error.UserFacingFailure
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme

private data object ReducedMotionScale : MotionDurationScale {
    override val scaleFactor: Float = 0f
}

class StateComponentsTest {
    @get:Rule
    val composeRule = createComposeRule(effectContext = ReducedMotionScale)

    @Test
    fun failureCardShowsTextAndDispatchesOnlySelectedRecoveryAction() {
        val actions = mutableListOf<RecoveryAction>()
        composeRule.setContent {
            DeviceBridgeTheme {
                FailureCard(
                    title = "Передача прервана",
                    message = "Соединение с браузером потеряно.",
                    failure = UserFacingFailure(
                        code = FailureCode.TEXT_CONNECTION_LOST,
                        severity = FailureSeverity.RECOVERABLE,
                        recoveryActions = setOf(
                            RecoveryAction.RETRY,
                            RecoveryAction.SELECT_SESSION,
                        ),
                    ),
                    onRecoveryAction = actions::add,
                    onCopyTechnicalDetails = {},
                )
            }
        }

        composeRule.onNodeWithText("Передача прервана").assertIsDisplayed()
        composeRule.onNodeWithText("Соединение с браузером потеряно.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Действие восстановления: Повторить")
            .performClick()

        assertEquals(listOf(RecoveryAction.RETRY), actions)
    }

    @Test
    fun loadingAndEmptyStatesExposeTextualMeaningWhenMotionIsDisabled() {
        composeRule.setContent {
            DeviceBridgeTheme {
                androidx.compose.foundation.layout.Column {
                    LoadingState(label = "Загрузка операций")
                    EmptyState(
                        title = "Операций пока нет",
                        message = "Завершённые передачи появятся здесь.",
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Загрузка операций").assertIsDisplayed()
        composeRule.onNodeWithText("Операций пока нет").assertIsDisplayed()
    }

    @Test
    fun disabledPrimaryActionRemainsSemanticallyDisabled() {
        composeRule.setContent {
            DeviceBridgeTheme {
                PrimaryActionButton(
                    label = "Отправить",
                    enabled = false,
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Основное действие: Отправить")
            .assertIsNotEnabled()
    }

    @Test
    fun successAndCancelledSurfacesCanBeCapturedWithoutLosingTextStatus() {
        composeRule.setContent {
            DeviceBridgeTheme {
                androidx.compose.foundation.layout.Column {
                    StateSurface(
                        statusLabel = "Успешно",
                        title = "Файл передан",
                        message = "Передача завершена.",
                        tone = StateTone.SUCCESS,
                    )
                    StateSurface(
                        statusLabel = "Отменено",
                        title = "Передача отменена",
                        message = "Файл не был изменён.",
                        tone = StateTone.NEUTRAL,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Успешно").assertIsDisplayed()
        composeRule.onNodeWithText("Отменено").assertIsDisplayed()
        val screenshot = composeRule.onRoot().captureToImage()
        assertTrue(screenshot.width > 0)
        assertTrue(screenshot.height > 0)
    }

    @Test
    fun primaryActionExposesFocusedAndPressedVisualStates() {
        composeRule.setContent {
            val interactionSource = remember { MutableInteractionSource() }
            LaunchedEffect(interactionSource) {
                interactionSource.emit(FocusInteraction.Focus())
            }
            DeviceBridgeTheme {
                PrimaryActionButton(
                    label = "Продолжить",
                    onClick = {},
                    interactionSource = interactionSource,
                )
            }
        }

        val action = composeRule.onNodeWithContentDescription(
            "Основное действие: Продолжить",
        )
        composeRule.waitForIdle()
        val focusedScreenshot = action.captureToImage()
        assertTrue(focusedScreenshot.width > 0)

        action.performTouchInput { down(center) }
        val pressedScreenshot = action.captureToImage()
        assertTrue(pressedScreenshot.width > 0)
        action.performTouchInput { up() }
    }
    @Test
    fun quickActionHidesDecorativeSymbolFromAccessibility() {
        composeRule.setContent {
            DeviceBridgeTheme {
                QuickActionCard(
                    symbol = "⇧",
                    title = "Файлы",
                    supportingText = "Передать документ",
                    enabled = true,
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithText("⇧", useUnmergedTree = true)
            .assert(isHiddenFromAccessibility())
        composeRule.onNodeWithText("Файлы").assertIsDisplayed()
    }
}
