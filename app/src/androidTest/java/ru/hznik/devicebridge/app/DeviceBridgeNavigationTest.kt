package ru.hznik.devicebridge.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import ru.hznik.devicebridge.feature.file.FileScreen
import ru.hznik.devicebridge.feature.file.FileUiState
import ru.hznik.devicebridge.feature.history.HistoryLoadState
import ru.hznik.devicebridge.feature.history.HistoryScreen
import ru.hznik.devicebridge.feature.history.HistoryUiState
import ru.hznik.devicebridge.feature.settings.SettingsScreen
import ru.hznik.devicebridge.feature.settings.SettingsUiState

@RunWith(AndroidJUnit4::class)
class DeviceBridgeNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appStartsOnHome() {
        setAppContent()

        composeRule.onNodeWithText("DeviceBridge").performScrollTo().assertIsDisplayed()
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

        composeRule.onNodeWithText("DeviceBridge").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Раздел Главная").assertIsSelected()
    }

    @Test
    fun textQuickActionOpensFlowAndExplicitBackReturnsHome() {
        setAppContent(withActiveSession = true)

        composeRule.onNodeWithText("Текст").performScrollTo().performClick()

        composeRule.onNodeWithText("Текст и ссылки").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Вернуться на главный экран")
            .performClick()
        composeRule.onNodeWithText("DeviceBridge").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun fileQuickActionOpensOnlyWithActiveSessionAndBackReturnsHome() {
        setAppContent(withActiveSession = true)

        composeRule.onNodeWithText("Файлы").performScrollTo().performClick()
        composeRule.onNodeWithText("Потоковая передача", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Вернуться на главный экран").performClick()
        composeRule.onNodeWithText("DeviceBridge").performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithText("DeviceBridge").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun navigationAdaptsAcrossResizeAndRestorationWithoutLosingDestination() {
        var width by mutableStateOf(400.dp)
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            CompositionLocalProvider(LocalNavigationWidthOverride provides width) {
                DeviceBridgeTheme {
                    testApp(withActiveSession = false)
                }
            }
        }

        composeRule.onNodeWithTag("top_level_navigation_bar").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Раздел История").performClick()
        composeRule.onNodeWithText("История передач").assertIsDisplayed()

        composeRule.runOnIdle { width = 700.dp }

        composeRule.onNodeWithTag("top_level_navigation_rail").assertIsDisplayed()
        composeRule.onAllNodesWithTag("top_level_navigation_bar").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Раздел История").assertIsSelected()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("top_level_navigation_rail").assertIsDisplayed()
        composeRule.onNodeWithText("История передач").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Раздел История").assertIsSelected()
    }

    @Test
    fun allScreensRemainStableAcrossPhoneLargeLightDarkAndFontScaleMatrix() {
        var width by mutableStateOf(400.dp)
        var darkTheme by mutableStateOf(true)
        var fontScale by mutableStateOf(2f)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalNavigationWidthOverride provides width,
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
            ) {
                DeviceBridgeTheme(darkTheme = darkTheme) {
                    Box(
                        modifier = Modifier
                            .width(width)
                            .height(1_000.dp)
                            .testTag("screen_matrix_surface"),
                    ) {
                        testApp(
                            withActiveSession = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("top_level_navigation_bar").assertIsDisplayed()
        assertMatrixSurface(widthPx = 400)
        composeRule.onNodeWithText("Текст").performScrollTo().performClick()
        composeRule.onNodeWithText("Текст и ссылки").assertIsDisplayed()
        assertMatrixSurface(widthPx = 400)
        composeRule.onNodeWithContentDescription("Вернуться на главный экран").performClick()
        composeRule.onNodeWithText("Файлы").performScrollTo().performClick()
        composeRule.onNodeWithText("Потоковая передача", substring = true).assertIsDisplayed()
        assertMatrixSurface(widthPx = 400)
        composeRule.onNodeWithContentDescription("Вернуться на главный экран").performClick()
        composeRule.onNodeWithContentDescription("Раздел История").performClick()
        composeRule.onNodeWithText("История передач").assertIsDisplayed()
        assertMatrixSurface(widthPx = 400)
        composeRule.onNodeWithContentDescription("Раздел Настройки").performClick()
        composeRule.onNodeWithContentDescription("Заголовок экрана Настройки").assertIsDisplayed()
        assertMatrixSurface(widthPx = 400)

        composeRule.runOnIdle {
            width = 700.dp
            darkTheme = false
            fontScale = 1f
        }
        composeRule.onNodeWithTag("top_level_navigation_rail").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Заголовок экрана Настройки").assertIsDisplayed()
        assertMatrixSurface(widthPx = 700)
        composeRule.onNodeWithContentDescription("Раздел Главная").performClick()
        composeRule.onNodeWithText("DeviceBridge").performScrollTo().assertIsDisplayed()
        assertMatrixSurface(widthPx = 700)
        composeRule.onNodeWithText("Текст").performScrollTo().performClick()
        composeRule.onNodeWithText("Текст и ссылки").assertIsDisplayed()
        assertMatrixSurface(widthPx = 700)
        composeRule.onNodeWithContentDescription("Вернуться на главный экран").performClick()
        composeRule.onNodeWithText("Файлы").performScrollTo().performClick()
        composeRule.onNodeWithText("Потоковая передача", substring = true).assertIsDisplayed()
        assertMatrixSurface(widthPx = 700)
        composeRule.onNodeWithContentDescription("Вернуться на главный экран").performClick()
        composeRule.onNodeWithContentDescription("Раздел История").performClick()
        composeRule.onNodeWithText("История передач").assertIsDisplayed()
        assertMatrixSurface(widthPx = 700)
    }

    @Test
    fun visibleClickTargetsAcrossAllScreensHaveNamesRolesAndMinimumTouchSize() {
        setAppContent(withActiveSession = true)
        assertVisibleClickTargetsAccessible("Home")

        composeRule.onNodeWithText("Текст").performScrollTo().performClick()
        assertVisibleClickTargetsAccessible("Text")
        composeRule.onNodeWithContentDescription("Вернуться на главный экран").performClick()

        composeRule.onNodeWithText("Файлы").performScrollTo().performClick()
        assertVisibleClickTargetsAccessible("Files")
        composeRule.onNodeWithContentDescription("Вернуться на главный экран").performClick()

        composeRule.onNodeWithContentDescription("Раздел История").performClick()
        assertVisibleClickTargetsAccessible("History")

        composeRule.onNodeWithContentDescription("Раздел Настройки").performClick()
        assertVisibleClickTargetsAccessible("Settings")
    }
    private fun assertVisibleClickTargetsAccessible(screen: String) {
        composeRule.waitForIdle()
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val minimumTouchSizePx = with(composeRule.density) { 48.dp.toPx() }
        val failures = composeRule.onAllNodes(hasClickAction())
            .fetchSemanticsNodes()
            .filter { node ->
                val bounds = node.boundsInRoot
                bounds.right > rootBounds.left &&
                    bounds.left < rootBounds.right &&
                    bounds.bottom > rootBounds.top &&
                    bounds.top < rootBounds.bottom
            }
            .flatMap { node ->
                val descriptions = node.config
                    .getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty()
                val text = node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text }
                val editableText = node.config
                    .getOrNull(SemanticsProperties.EditableText)
                    ?.text
                    .orEmpty()
                val hasAccessibleName = (descriptions + text + editableText)
                    .any(String::isNotBlank)
                val accessibleLabel = (descriptions + text + editableText)
                    .filter(String::isNotBlank)
                    .joinToString(" | ")
                buildList {
                    if (!hasAccessibleName) add("node ${node.id} has no accessible name")
                    val isEditable = node.config.contains(SemanticsActions.SetText)
                    if (!isEditable && !node.config.contains(SemanticsProperties.Role)) {
                        add("node ${node.id} [$accessibleLabel] has no role")
                    }
                    val touchBounds = node.touchBoundsInRoot
                    if (touchBounds.width + 0.5f < minimumTouchSizePx ||
                        touchBounds.height + 0.5f < minimumTouchSizePx
                    ) {
                        add(
                            "node ${node.id} [$accessibleLabel] touch target is " +
                                "${touchBounds.width}x${touchBounds.height}px",
                        )
                    }
                }
            }

        assertTrue(
            "$screen accessibility audit failed:\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }
    private fun assertMatrixSurface(widthPx: Int) {
        val screenshot = composeRule.onNodeWithTag("screen_matrix_surface").captureToImage()
        assertEquals(widthPx, screenshot.width)
        assertTrue(screenshot.height > 0)
    }
    private fun setAppContent(withActiveSession: Boolean = false) {
        composeRule.setContent {
            DeviceBridgeTheme {
                testApp(withActiveSession)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun testApp(
        withActiveSession: Boolean,
        modifier: Modifier = Modifier,
    ) {
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
            modifier = modifier,
            homeContent = { onOpenText, onOpenFiles ->
                HomeScreen(
                    uiState = sessionState,
                    onOpenText = onOpenText,
                    onOpenFiles = onOpenFiles,
                )
            },
            textContent = { onBack, _ ->
                TextScreen(
                    uiState = TextUiState(),
                    onBack = onBack,
                )
            },
            fileContent = { onBack, _ -> FileScreen(FileUiState(), onBack = onBack) },
            historyContent = {
                HistoryScreen(
                    HistoryUiState(loadState = HistoryLoadState.EMPTY),
                    onAction = {},
                )
            },
            settingsContent = {
                SettingsScreen(
                    SettingsUiState(loadState = ru.hznik.devicebridge.feature.settings.SettingsLoadState.CONTENT),
                    onAction = {},
                )
            },
        )
    }
}
