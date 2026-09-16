package ru.hznik.devicebridge.app

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.MainActivity

@RunWith(AndroidJUnit4::class)
class ShareIntentActivityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun manifestAcceptsTextPlainShareAndActivityOpensUnconfirmedDraft() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val implicitIntent = Intent(Intent.ACTION_SEND)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setType("text/plain")
            .setPackage(context.packageName)
        val resolved = context.packageManager.queryIntentActivities(
            implicitIntent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue(
            resolved.any { it.activityInfo.name == MainActivity::class.java.name },
        )

        val launchIntent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Черновик из другого приложения")

        ActivityScenario.launch<MainActivity>(launchIntent).use {
            composeRule.onNodeWithText("Текст и ссылки").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Текст для отправки")
                .assertTextContains("Черновик из другого приложения")
            composeRule.onNodeWithText("Отправить").assertIsNotEnabled()
        }
    }
}
