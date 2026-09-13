package ru.hznik.devicebridge.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.MainActivity

@RunWith(AndroidJUnit4::class)
class MainActivityRestorationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun selectedDestinationSurvivesActivityRecreation() {
        composeRule.onNodeWithContentDescription("Раздел История").performClick()
        composeRule.onNodeWithText("История передач").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("История передач").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Раздел История").assertIsSelected()
    }
}
