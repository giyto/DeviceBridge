package ru.hznik.devicebridge.core.ui

import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isHiddenFromAccessibility
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
    fun graphiteHeadersMetadataAndCancelledOperationKeepTextualSemantics() {
        composeRule.setContent {
            DeviceBridgeTheme {
                androidx.compose.foundation.layout.Column {
                    ScreenHeader(
                        eyebrow = "Локальное устройство",
                        title = "DeviceBridge",
                        supportingText = "Телефон и компьютер рядом",
                    )
                    SectionHeader(
                        title = "Передачи",
                        supportingText = "Текущая сессия",
                    )
                    MetadataRow(label = "Файл", value = "long-name.zip", monospace = true)
                    OperationalItem(
                        statusLabel = "Отменено",
                        title = "long-name.zip",
                        metadata = "С телефона · 2 МБ",
                        tone = StateTone.CANCELLED,
                    )
                    DestructiveActionButton(label = "Отозвать доступ", onClick = {})
                }
            }
        }

        composeRule.onNodeWithText("DeviceBridge").assertIsDisplayed()
        composeRule.onNodeWithText("Передачи").assertIsDisplayed()
        composeRule.onAllNodesWithText("long-name.zip").assertCountEquals(2)
        composeRule.onNodeWithContentDescription(
            "Отменено. long-name.zip. С телефона · 2 МБ",
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Опасное действие: Отозвать доступ")
            .assertIsDisplayed()
    }

    @Test
    fun loadingPrimaryActionIsDisabledAndKeepsItsAccessibleName() {
        composeRule.setContent {
            DeviceBridgeTheme {
                PrimaryActionButton(
                    label = "Отправляем",
                    loading = true,
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Основное действие: Отправляем")
            .assertIsNotEnabled()
    }
    @Test
    fun signalFlowKeepsAStaticAccessibleFallbackWhenMotionIsDisabled() {
        composeRule.setContent {
            DeviceBridgeTheme {
                SignalFlowIndicator(
                    active = true,
                    statusLabel = "Связь активна",
                )
            }
        }

        val indicator = composeRule.onNodeWithContentDescription("Связь активна")
        indicator.assertIsDisplayed()
        val screenshot = indicator.captureToImage()
        assertTrue(screenshot.width > 0)
        assertTrue(screenshot.height > 0)
    }
    @Test
    fun quickActionHidesDecorativeSymbolFromAccessibility() {
        composeRule.setContent {
            DeviceBridgeTheme {
                QuickActionCard(
                    icon = BridgeIcons.Folder,
                    title = "Файлы",
                    supportingText = "Передать документ",
                    enabled = true,
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("quick_action_icon", useUnmergedTree = true)
            .assert(isHiddenFromAccessibility())
            .assertWidthIsEqualTo(28.dp)
        composeRule.onNodeWithText("Файлы").assertIsDisplayed()
    }
}
