package ru.hznik.devicebridge.feature.text

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferDirection
import ru.hznik.devicebridge.domain.text.TextTransferStatus
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme

@RunWith(AndroidJUnit4::class)
class TextScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingStateIsExplicit() {
        setScreen(TextUiState(isLoading = true))
        composeRule.onNodeWithText("Загружаем передачу текста…").assertIsDisplayed()
    }

    @Test
    fun emptyStateKeepsUnavailableActionsHonest() {
        setScreen(TextUiState())
        composeRule.onNodeWithText("Нет подключённых браузеров").assertIsDisplayed()
        composeRule.onNodeWithText("Передач пока нет").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Отправить").assertIsNotEnabled()
    }

    @Test
    fun previewRecipientSuccessAndExplicitActionsAreAccessible() {
        val actions = mutableListOf<TextAction>()
        var pasteRequests = 0
        val openRequests = mutableListOf<String>()
        val sessionId = BrowserSessionId("session-1")
        setScreen(
            state = TextUiState(
                draft = "https://example.com",
                recipients = listOf(
                    TextRecipientUiState(
                        id = sessionId,
                        browserLabel = "Яндекс Браузер",
                        sourceIpv4 = "192.168.1.3",
                        selected = true,
                    ),
                ),
                selectedSessionId = sessionId,
                preview = TextPreviewUiState(
                    content = "https://example.com",
                    contentKind = TextContentKind.LINK,
                    utf8Bytes = 19,
                ),
                successMessage = "Текст доставлен.",
            ),
            onAction = actions::add,
            onPasteRequested = { pasteRequests += 1 },
            onOpenLinkRequested = openRequests::add,
        )

        composeRule.onNodeWithText("Яндекс Браузер").assertIsDisplayed()
        composeRule.onNodeWithText("Тип: Ссылка").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Предпросмотр содержимого: https://example.com",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Текст доставлен.").assertIsDisplayed()
        composeRule.onNodeWithText("Отправить")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithContentDescription("Вставить текст из буфера")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithContentDescription("Открыть ссылку")
            .performScrollTo()
            .performClick()

        assertEquals(listOf(TextAction.SendClicked), actions)
        assertEquals(1, pasteRequests)
        assertEquals(listOf("https://example.com"), openRequests)
    }

    @Test
    fun errorAndFailedTransferExposeRetryWithoutHidingPlainText() {
        val actions = mutableListOf<TextAction>()
        val messageId = TextMessageId("failed-1")
        setScreen(
            state = TextUiState(
                errorMessage = "Соединение потеряно.",
                items = listOf(
                    TextItemUiState(
                        id = messageId,
                        sessionId = BrowserSessionId("session-1"),
                        browserLabel = "Edge",
                        content = "<script>alert('plain')</script>",
                        contentKind = TextContentKind.TEXT,
                        direction = TextTransferDirection.ANDROID_TO_BROWSER,
                        status = TextTransferStatus.FAILED,
                        timestampEpochMillis = 1_000_000,
                        canRetry = true,
                        isRetrying = false,
                    ),
                ),
            ),
            onAction = actions::add,
        )

        composeRule.onNodeWithText("Соединение потеряно.").assertIsDisplayed()
        composeRule.onNodeWithText("<script>alert('plain')</script>")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Повторить отправку в Edge")
            .performScrollTo().performClick()

        assertEquals(listOf(TextAction.RetryClicked(messageId)), actions)
    }

    @Test
    fun editorReceivesKeyboardFocusAndLargeFontLayoutRemainsScrollable() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2f),
            ) {
                DeviceBridgeTheme(darkTheme = true) {
                    TextScreen(
                        uiState = TextUiState(
                            recipients = listOf(
                                TextRecipientUiState(
                                    id = BrowserSessionId("session-1"),
                                    browserLabel = "Chrome",
                                    sourceIpv4 = "192.168.1.2",
                                    selected = true,
                                ),
                            ),
                            selectedSessionId = BrowserSessionId("session-1"),
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Текст для отправки")
            .performClick()
            .assertIsFocused()
        composeRule.onNodeWithText("Отправить").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Текущая лента").performScrollTo().assertIsDisplayed()
    }

    private fun setScreen(
        state: TextUiState,
        onAction: (TextAction) -> Unit = {},
        onPasteRequested: () -> Unit = {},
        onOpenLinkRequested: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            DeviceBridgeTheme {
                TextScreen(
                    uiState = state,
                    onAction = onAction,
                    onPasteRequested = onPasteRequested,
                    onOpenLinkRequested = onOpenLinkRequested,
                )
            }
        }
    }
}
