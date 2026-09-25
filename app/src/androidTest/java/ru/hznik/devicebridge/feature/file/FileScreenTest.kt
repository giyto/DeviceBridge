package ru.hznik.devicebridge.feature.file

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.file.FileDraftId
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.feature.common.RecipientUiState
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme

@RunWith(AndroidJUnit4::class)
class FileScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun autoAcceptedTransferShowsLabelSenderAndAccessibleDescription() {
        setScreen(
            FileUiState(
                transfers = listOf(
                    item("auto", FileTransferPhase.TRANSFERRING, 5, 10).copy(
                        senderLabel = "Chrome • Windows",
                        autoAccepted = true,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Принят автоматически от Chrome • Windows", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Этап передачи auto.bin: Передаётся, принят автоматически",
        ).assertIsDisplayed()
    }

    @Test
    fun pausedAutoAcceptKeepsManualAcceptanceAvailable() {
        val actions = mutableListOf<FileAction>()
        setScreen(
            FileUiState(
                transfers = listOf(
                    item("paused", FileTransferPhase.CONNECTING, 0, 10).copy(
                        autoAcceptPaused = true,
                    ),
                ),
                hasDefaultDestination = true,
            ),
            onAction = { actions += it },
        )

        composeRule.onNodeWithText(
            "Автоприём приостановлен: папка недоступна. Примите файл вручную.",
            useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Принять в выбранную папку", useUnmergedTree = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText("Выбрать другую папку", useUnmergedTree = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText("Принять и выбрать папку", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        assertEquals(listOf<FileAction>(FileAction.ApproveIncoming(FileTransferId("paused"))), actions)
    }

    @Test
    fun emptyStateOffersFileSelection() {
        setScreen(FileUiState())
        composeRule.onNodeWithText("Файлы").assertIsDisplayed()
        composeRule.onNodeWithText("Добавить файлы").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Подтвердить отправку файлов")
            .assertIsNotEnabled()
    }

    @Test
    fun editableDraftExposesAccessibleRemoveClearAndAddActions() {
        val actions = mutableListOf<FileAction>()
        val draftId = FileDraftId("draft-report")
        composeRule.setContent {
            DeviceBridgeTheme {
                FileScreen(
                    uiState = FileUiState(
                        selection = listOf(
                            FileDraftItem(
                                id = draftId,
                                displayName = "report.pdf",
                                sizeBytes = 42,
                                mimeType = "application/pdf",
                                sha256 = "a".repeat(64),
                                sourceIdentity = "content://report",
                                sourceLease = NoOpDraftSourceLease,
                            ),
                        ),
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Удалить report.pdf из выбранных")
            .performClick()
        composeRule.onNodeWithContentDescription("Очистить выбранные файлы")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Добавить файлы").assertIsDisplayed()
        org.junit.Assert.assertEquals(listOf(FileAction.RemoveDraftItem(draftId)), actions)
    }

    @Test
    fun multipleTransfersExposeProgressAndActions() {
        setScreen(
            FileUiState(
                transfers = listOf(
                    item("upload", FileTransferPhase.TRANSFERRING, 50, 100),
                    item("done", FileTransferPhase.COMPLETED, 100, 100),
                ),
            ),
        )
        composeRule.onNodeWithText("upload.bin").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("50%").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Отменить передачу upload.bin").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Открыть файл done.bin").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun largeFontKeepsPrimaryActionsAvailable() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                DeviceBridgeTheme(darkTheme = true) { FileScreen(FileUiState()) }
            }
        }
        composeRule.onNodeWithText("Добавить файлы").assertIsDisplayed()
    }

    @Test
    fun storageAndChecksumFailuresAreReadableWithoutInternalDetails() {
        setScreen(
            FileUiState(
                transfers = listOf(
                    item("storage", FileTransferPhase.FAILED, 0, 100)
                        .copy(failure = FileTransferFailure.StorageUnavailable),
                    item("checksum", FileTransferPhase.FAILED, 100, 100)
                        .copy(failure = FileTransferFailure.ChecksumMismatch),
                    item("space", FileTransferPhase.FAILED, 0, 100)
                        .copy(failure = FileTransferFailure.InsufficientSpace),
                ),
            ),
        )

        composeRule.onNodeWithText("Выбранная папка недоступна. Выберите другую папку.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("На устройстве недостаточно свободного места.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Контрольная сумма не совпала. Повторите передачу.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun keptUploadPartOffersContinueAndRunningResumeShowsWhereItContinued() {
        val actions = mutableListOf<FileAction>()
        setScreen(
            FileUiState(
                transfers = listOf(
                    item("kept", FileTransferPhase.FAILED, 40, 100)
                        .copy(failure = FileTransferFailure.StreamFailed, resumableBytes = 40),
                    item("running", FileTransferPhase.TRANSFERRING, 60, 100)
                        .copy(resumedFromBytes = 40),
                ),
            ),
            onAction = actions::add,
        )

        composeRule.onNodeWithContentDescription("Продолжить передачу kept.bin")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("Передача прервалась. Сохранено 40 Б из 100 Б, её можно продолжить.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Продолжение с 40 Б").performScrollTo().assertIsDisplayed()
        assertEquals(listOf<FileAction>(FileAction.Retry(FileTransferId("kept"))), actions)
    }

    @Test
    fun incomingOfferLetsUserUseSavedFolderOrChooseAnother() {
        val actions = mutableListOf<FileAction>()
        val incoming = item("incoming", FileTransferPhase.CONNECTING, 0, 100)
        composeRule.setContent {
            DeviceBridgeTheme {
                FileScreen(
                    uiState = FileUiState(
                        transfers = listOf(incoming),
                        hasDefaultDestination = true,
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("Принять в выбранную папку")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Выбрать другую папку")
            .performScrollTo()
            .performClick()

        assertEquals(
            listOf(
                FileAction.ApproveIncoming(incoming.id),
                FileAction.ChangeIncomingDestination(incoming.id),
            ),
            actions,
        )
    }

    @Test
    fun longFilenameAndTerminalStatesExposeOnlyApplicableActionsAtLargeFont() {
        val longName = "quarterly-report-".repeat(14) + ".pdf"
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                DeviceBridgeTheme(darkTheme = true) {
                    FileScreen(
                        FileUiState(
                            transfers = listOf(
                                item("verify", FileTransferPhase.VERIFYING, 100, 100),
                                item("cancelled", FileTransferPhase.CANCELLED, 60, 100),
                                item("completed", FileTransferPhase.COMPLETED, 100, 100),
                                item("failed", FileTransferPhase.FAILED, 25, 100).copy(
                                    displayName = longName,
                                    mimeType = "application/pdf",
                                    failure = FileTransferFailure.StreamFailed,
                                ),
                            ),
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithText(longName).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Тип файла PDF")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Отменить передачу verify.bin")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Повторить передачу cancelled.bin")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Отменить передачу cancelled.bin")
            .assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Открыть файл completed.bin")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Повторить передачу $longName")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("100%").assertCountEquals(0)
    }

    @Test
    fun transferStageIsLiveRegionAndProgressDoesNotStealFocus() {
        var state by mutableStateOf(
            FileUiState(
                transfers = listOf(
                    item("focus", FileTransferPhase.TRANSFERRING, 10, 100),
                ),
            ),
        )
        composeRule.setContent {
            DeviceBridgeTheme {
                FileScreen(uiState = state)
            }
        }
        val polite = SemanticsMatcher.expectValue(
            SemanticsProperties.LiveRegion,
            LiveRegionMode.Polite,
        )
        composeRule.onNodeWithContentDescription("Этап передачи focus.bin: Передаётся")
            .performScrollTo()
            .assert(polite)
        val progress = composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).performScrollTo()
        val doesNotCaptureFocusOrAnnounceEveryUpdate = SemanticsMatcher(
            "progress does not expose focus or live-region state",
        ) {
            !it.config.contains(SemanticsProperties.Focused) &&
                !it.config.contains(SemanticsProperties.LiveRegion)
        }
        progress.assert(doesNotCaptureFocusOrAnnounceEveryUpdate)

        composeRule.runOnIdle {
            state = state.copy(
                transfers = listOf(
                    item("focus", FileTransferPhase.TRANSFERRING, 50, 100),
                ),
            )
        }

        progress.assert(doesNotCaptureFocusOrAnnounceEveryUpdate)
        composeRule.onNodeWithText("50%").assertIsDisplayed()
    }
    @Test
    fun confirmAndRetryCommandsAreNotRepeatedAfterRecompositionOrRestoration() {
        val actions = mutableListOf<FileAction>()
        val sessionId = BrowserSessionId("session-effect-test")
        val failedId = FileTransferId("failed-effect-test")
        var state by mutableStateOf(
            FileUiState(
                selection = listOf(
                    FileDraftItem(
                        id = FileDraftId("draft-effect-test"),
                        displayName = "notes.txt",
                        sizeBytes = 42,
                        mimeType = "text/plain",
                        sha256 = "a".repeat(64),
                        sourceIdentity = "content://notes",
                        sourceLease = NoOpDraftSourceLease,
                    ),
                ),
                recipients = listOf(
                    RecipientUiState(sessionId, "Chrome", "192.168.1.2", selected = true),
                ),
                selectedSessionId = sessionId,
                transfers = listOf(
                    item("failed-effect-test", FileTransferPhase.FAILED, 10, 42).copy(
                        failure = FileTransferFailure.StreamFailed,
                    ),
                ),
            ),
        )
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            DeviceBridgeTheme {
                FileScreen(uiState = state, onAction = actions::add)
            }
        }

        composeRule.onNodeWithContentDescription("Подтвердить отправку файлов")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithContentDescription("Повторить передачу failed-effect-test.bin")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            state = state.copy(errorMessage = "Проверка recomposition")
        }
        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertEquals(
                listOf(FileAction.ConfirmSend, FileAction.Retry(failedId)),
                actions,
            )
        }
    }
    @Test
    fun recipientCardUsesRadioButtonRole() {
        val sessionId = BrowserSessionId("accessible-file-recipient")
        setScreen(
            FileUiState(
                recipients = listOf(
                    RecipientUiState(
                        id = sessionId,
                        browserLabel = "Edge",
                        sourceIpv4 = "192.168.1.3",
                        selected = true,
                    ),
                ),
                selectedSessionId = sessionId,
            ),
        )
        val radioButton = SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            Role.RadioButton,
        )

        composeRule.onNodeWithContentDescription("Выбрать браузер Edge")
            .assert(radioButton)
    }

    @Test
    fun feedbackCardIsAnExplicitDismissAction() {
        setScreen(FileUiState(errorMessage = "Файл недоступен."))
        val button = SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            Role.Button,
        )

        composeRule.onNodeWithContentDescription(
            "Файл недоступен. Закрыть сообщение",
        ).assert(button)
    }
    private fun setScreen(state: FileUiState, onAction: (FileAction) -> Unit = {}) {
        composeRule.setContent { DeviceBridgeTheme { FileScreen(state, onAction = onAction) } }
    }

    private fun item(id: String, phase: FileTransferPhase, bytes: Long, size: Long) =
        FileTransferItemUiState(
            id = FileTransferId(id), displayName = "$id.bin", sizeBytes = size,
            mimeType = "application/octet-stream",
            direction = FileTransferDirection.BROWSER_TO_ANDROID, phase = phase,
            bytesTransferred = bytes, speedBytesPerSecond = 10, failure = null,
        )

    private data object NoOpDraftSourceLease : DraftSourceLease {
        override fun promote(transferId: FileTransferId) = true
        override fun rollback(transferId: FileTransferId) = Unit
        override fun commit(transferId: FileTransferId) = Unit
        override fun release() = Unit
    }
}
