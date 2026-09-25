package ru.hznik.devicebridge.feature.text

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
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
import ru.hznik.devicebridge.feature.common.RecipientUiState
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
                    RecipientUiState(
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
    fun feedExposesParticipantDirectionTimeStatusAndLongUrlAction() {
        val longUrl = "https://example.com/" + "very-long-segment/".repeat(18) + "?source=devicebridge"
        val openRequests = mutableListOf<String>()
        setScreen(
            state = TextUiState(
                items = listOf(
                    TextItemUiState(
                        id = TextMessageId("long-link"),
                        browserLabel = "Firefox",
                        content = longUrl,
                        contentKind = TextContentKind.LINK,
                        direction = TextTransferDirection.BROWSER_TO_ANDROID,
                        status = TextTransferStatus.DELIVERED,
                        timestampEpochMillis = 1_700_000_000_000,
                        canRetry = false,
                        isRetrying = false,
                    ),
                ),
            ),
            onOpenLinkRequested = openRequests::add,
        )

        composeRule.onNodeWithText("Отправитель: Firefox")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("На телефон", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Время:", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Доставлено").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Доставлено", substring = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        composeRule.onNodeWithText(longUrl).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Открыть ссылку от Firefox")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(listOf(longUrl), openRequests)
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
                                RecipientUiState(
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

        composeRule.onNodeWithContentDescription("Область создания сообщения")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Отправить текст в выбранный браузер")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Текст для отправки")
            .performClick()
            .assertIsFocused()
        composeRule.onNodeWithText("Отправить").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Текущая лента").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingBlankAreaClearsEditorFocusAndKeepsDraft() {
        var state by mutableStateOf(TextUiState(draft = "Черновик"))
        composeRule.setContent {
            DeviceBridgeTheme {
                TextScreen(
                    uiState = state,
                    onAction = { action ->
                        if (action is TextAction.DraftChanged) {
                            state = state.copy(draft = action.value)
                        }
                    },
                )
            }
        }

        val editor = composeRule.onNodeWithContentDescription("Текст для отправки")
        editor.performClick()
        editor.performTextReplacement("Текст остаётся")
        editor.assertIsFocused()

        composeRule.onNodeWithTag("text-screen")
            .performTouchInput { click(Offset(4f, 4f)) }

        editor.assertIsNotFocused().assertTextContains("Текст остаётся")
    }

    @Test
    fun recipientUsesOneRadioButtonSemanticsNode() {
        val sessionId = BrowserSessionId("accessible-recipient")
        setScreen(
            TextUiState(
                recipients = listOf(
                    RecipientUiState(
                        id = sessionId,
                        browserLabel = "Chrome",
                        sourceIpv4 = "192.168.1.2",
                        selected = true,
                    ),
                    RecipientUiState(
                        id = BrowserSessionId("accessible-recipient-2"),
                        browserLabel = "Edge",
                        sourceIpv4 = "192.168.1.3",
                        selected = false,
                    ),
                ),
                selectedSessionId = sessionId,
            ),
        )
        val radioButton = SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            Role.RadioButton,
        )

        composeRule.onAllNodesWithContentDescription(
            "Выбрать Chrome",
            useUnmergedTree = true,
        ).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(
            "Выбрать Chrome",
            useUnmergedTree = true,
        ).assert(radioButton)
    }

    @Test
    fun feedbackCardIsAnExplicitDismissAction() {
        setScreen(TextUiState(errorMessage = "Соединение потеряно."))
        val button = SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            Role.Button,
        )

        composeRule.onNodeWithContentDescription(
            "Соединение потеряно. Закрыть сообщение",
        ).assert(button)
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
