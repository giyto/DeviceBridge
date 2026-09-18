package ru.hznik.devicebridge.feature.file

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.file.FileDraftId
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme

@RunWith(AndroidJUnit4::class)
class FileScreenTest {
    @get:Rule val composeRule = createComposeRule()

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
                ),
            ),
        )

        composeRule.onNodeWithText("Папка недоступна или на устройстве недостаточно места.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Контрольная сумма не совпала. Повторите передачу.")
            .performScrollTo()
            .assertIsDisplayed()
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

        assert(actions.contains(FileAction.ApproveIncoming(incoming.id)))
        assert(actions.contains(FileAction.ChangeIncomingDestination(incoming.id)))
    }

    private fun setScreen(state: FileUiState) {
        composeRule.setContent { DeviceBridgeTheme { FileScreen(state) } }
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
