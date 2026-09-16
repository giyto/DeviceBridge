package ru.hznik.devicebridge.feature.file

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme

@RunWith(AndroidJUnit4::class)
class FileScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun emptyStateOffersFileSelection() {
        setScreen(FileUiState())
        composeRule.onNodeWithText("Файлы").assertIsDisplayed()
        composeRule.onNodeWithText("Выбрать файлы").assertIsDisplayed()
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
        composeRule.onNodeWithText("Выбрать файлы").assertIsDisplayed()
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
}
