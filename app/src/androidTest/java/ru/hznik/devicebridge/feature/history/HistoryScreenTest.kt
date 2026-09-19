package ru.hznik.devicebridge.feature.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.hznik.devicebridge.domain.history.HistoryDirection
import ru.hznik.devicebridge.domain.history.HistoryFileMetadata
import ru.hznik.devicebridge.domain.history.HistoryFilter
import ru.hznik.devicebridge.domain.history.HistoryKind
import ru.hznik.devicebridge.domain.history.HistoryOperationId
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryRecordId
import ru.hznik.devicebridge.domain.history.HistoryStatus

class HistoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsTextLinkAndFileRecords() {
        composeRule.setContent {
            MaterialTheme {
                HistoryScreen(
                    uiState = HistoryUiState(
                        loadState = HistoryLoadState.CONTENT,
                        records = listOf(
                            record("text", HistoryKind.TEXT),
                            record("link", HistoryKind.LINK),
                            record("file", HistoryKind.FILE),
                        ),
                    ),
                    onAction = { },
                )
            }
        }

        composeRule.onNodeWithText("Текст").assertIsDisplayed()
        composeRule.onNodeWithText("Ссылка").assertIsDisplayed()
        composeRule.onNodeWithTag("history-list").performScrollToNode(hasText("Файл"))
        composeRule.onNodeWithText("Файл").assertIsDisplayed()
    }

    @Test
    fun filtersAreAccessibleActionsAndFilteredEmptyStateIsHonest() {
        var received: HistoryAction? = null
        composeRule.setContent {
            MaterialTheme {
                HistoryScreen(
                    uiState = HistoryUiState(
                        loadState = HistoryLoadState.EMPTY,
                        filter = HistoryFilter(kinds = setOf(HistoryKind.FILE)),
                    ),
                    onAction = { received = it },
                )
            }
        }

        composeRule.onNodeWithText("Файлы").performClick()
        assertEquals(HistoryAction.ToggleKind(HistoryKind.FILE), received)
        composeRule.onNodeWithText("По выбранным фильтрам ничего нет").assertIsDisplayed()
    }

    @Test
    fun fileDetailsShowSafeMetadataWithoutUnavailableOpenAction() {
        val file = record("file", HistoryKind.FILE)
        composeRule.setContent {
            MaterialTheme {
                HistoryScreen(
                    uiState = HistoryUiState(
                        loadState = HistoryLoadState.CONTENT,
                        records = listOf(file),
                        selectedRecord = file,
                    ),
                    onAction = { },
                )
            }
        }

        composeRule.onAllNodesWithText("video.mp4").assertCountEquals(2)
        composeRule.onNodeWithText("video/mp4").assertIsDisplayed()
        composeRule.onNodeWithText("c".repeat(64)).assertIsDisplayed()
        composeRule.onNodeWithText("Открыть файл").assertDoesNotExist()
    }

    @Test
    fun deleteRequiresExplicitConfirmationAction() {
        val item = record("text", HistoryKind.TEXT)
        val actions = mutableListOf<HistoryAction>()
        composeRule.setContent {
            MaterialTheme {
                HistoryScreen(
                    uiState = HistoryUiState(
                        loadState = HistoryLoadState.CONTENT,
                        records = listOf(item),
                        pendingDeleteId = item.id,
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("Удалить запись?").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Подтвердить: Удалить запись?").performClick()
        assertEquals(listOf(HistoryAction.ConfirmDelete), actions)
    }

    @Test
    fun clearRequiresExplicitConfirmationAction() {
        val item = record("text", HistoryKind.TEXT)
        val actions = mutableListOf<HistoryAction>()
        composeRule.setContent {
            MaterialTheme {
                HistoryScreen(
                    uiState = HistoryUiState(
                        loadState = HistoryLoadState.CONTENT,
                        records = listOf(item),
                        clearConfirmationVisible = true,
                    ),
                    onAction = actions::add,
                )
            }
        }
        composeRule.onNodeWithText("Очистить всю историю?").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Подтвердить: Очистить всю историю?").performClick()
        assertEquals(listOf(HistoryAction.ConfirmClear), actions)
    }

    @Test
    fun readFailureShowsRetryAndDoesNotClaimHistoryIsEmpty() {
        val actions = mutableListOf<HistoryAction>()
        composeRule.setContent {
            MaterialTheme {
                HistoryScreen(
                    uiState = HistoryUiState(
                        loadState = HistoryLoadState.ERROR,
                        errorMessage = "Не удалось прочитать локальную историю.",
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("История временно недоступна").assertIsDisplayed()
        composeRule.onNodeWithText("Операций пока нет").assertDoesNotExist()
        composeRule.onNodeWithText("Повторить").performClick()

        assertEquals(listOf(HistoryAction.RetryLoad), actions)
    }

    @Test
    fun loadingTransitionsToUsefulEmptyState() {
        var state by mutableStateOf(HistoryUiState(loadState = HistoryLoadState.LOADING))
        composeRule.setContent {
            MaterialTheme {
                HistoryScreen(uiState = state, onAction = {})
            }
        }

        composeRule.onNodeWithContentDescription("Загрузка истории").assertIsDisplayed()

        composeRule.runOnIdle {
            state = HistoryUiState(loadState = HistoryLoadState.EMPTY)
        }

        composeRule.onNodeWithText("История пока пуста").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Здесь появятся завершённые передачи текста, ссылок и файлов.",
        ).assertIsDisplayed()
    }

    @Test
    fun historyRecordCardExposesButtonRole() {
        val item = record("accessible", HistoryKind.TEXT)
        composeRule.setContent {
            MaterialTheme {
                HistoryScreen(
                    uiState = HistoryUiState(
                        loadState = HistoryLoadState.CONTENT,
                        records = listOf(item),
                    ),
                    onAction = {},
                )
            }
        }
        val button = SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            Role.Button,
        )

        composeRule.onNodeWithContentDescription("Открыть детали preview")
            .assert(button)
    }
    private fun record(id: String, kind: HistoryKind): HistoryRecord = HistoryRecord(
        id = HistoryRecordId("record-" + id),
        operationId = HistoryOperationId("operation-" + id),
        kind = kind,
        direction = HistoryDirection.BROWSER_TO_ANDROID,
        browserLabel = "Chrome",
        timestampEpochMillis = 1_700_000_000_000,
        status = if (kind == HistoryKind.FILE) {
            HistoryStatus.COMPLETED
        } else {
            HistoryStatus.DELIVERED
        },
        textPreview = if (kind == HistoryKind.FILE) null else "preview",
        file = if (kind == HistoryKind.FILE) {
            HistoryFileMetadata(
                displayName = "video.mp4",
                sizeBytes = 42,
                mimeType = "video/mp4",
                sha256 = "c".repeat(64),
            )
        } else {
            null
        },
        failureReason = null,
    )
}
