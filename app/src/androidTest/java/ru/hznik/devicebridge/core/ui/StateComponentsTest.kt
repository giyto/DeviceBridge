package ru.hznik.devicebridge.core.ui

import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isHiddenFromAccessibility
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme

private data object ReducedMotionScale : MotionDurationScale {
    override val scaleFactor: Float = 0f
}

class StateComponentsTest {
    @get:Rule
    val composeRule = createComposeRule(effectContext = ReducedMotionScale)

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
    fun graphiteHeadersMetadataAndDestructiveActionKeepTextualSemantics() {
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
                    DestructiveActionButton(label = "Отозвать доступ", onClick = {})
                }
            }
        }

        composeRule.onNodeWithText("DeviceBridge").assertIsDisplayed()
        composeRule.onNodeWithText("Передачи").assertIsDisplayed()
        composeRule.onNodeWithText("long-name.zip").assertIsDisplayed()
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
