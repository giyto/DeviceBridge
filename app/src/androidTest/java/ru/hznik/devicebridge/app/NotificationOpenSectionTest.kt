package ru.hznik.devicebridge.app

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.MainActivity

@RunWith(AndroidJUnit4::class)
class NotificationOpenSectionTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun textNotificationOpensTheTextScreen() {
        ActivityScenario.launch<MainActivity>(open("text")).use {
            composeRule.onNodeWithContentDescription("Текст для отправки").assertIsDisplayed()
        }
    }

    @Test
    fun filesNotificationOpensTheFilesScreen() {
        ActivityScenario.launch<MainActivity>(open("files")).use {
            composeRule.onNodeWithContentDescription("Добавить файлы в черновик").assertIsDisplayed()
        }
    }

    @Test
    fun requestNotificationOpensHomeFromAnotherScreen() {
        ActivityScenario.launch<MainActivity>(open("files")).use {
            composeRule.onNodeWithContentDescription("Добавить файлы в черновик").assertIsDisplayed()

            // A tapped notification reaches the running single-top activity as a new intent.
            ApplicationProvider.getApplicationContext<android.content.Context>().startActivity(
                open("home").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )

            composeRule.waitUntil(5_000) {
                runCatching {
                    composeRule.onNodeWithContentDescription("Раздел Главная").assertIsSelected()
                }.isSuccess
            }
        }
    }

    @Test
    fun recreationDoesNotOpenTheScreenAgain() {
        ActivityScenario.launch<MainActivity>(open("files")).use { scenario ->
            composeRule.onNodeWithText("← Назад").performClick()
            composeRule.onNodeWithContentDescription("Раздел Главная").assertIsSelected()

            scenario.recreate()

            composeRule.onNodeWithContentDescription("Раздел Главная").assertIsSelected()
        }
    }

    private fun open(section: String): Intent =
        Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SECTION, section)
}
