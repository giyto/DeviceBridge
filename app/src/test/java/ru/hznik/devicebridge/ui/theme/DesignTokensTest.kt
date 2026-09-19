package ru.hznik.devicebridge.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTokensTest {
    @Test
    fun spacingUsesFourPointGridAndFocusIsClearlyVisible() {
        val spacingValues = listOf(
            BridgeSpacing.xSmall,
            BridgeSpacing.small,
            BridgeSpacing.medium,
            BridgeSpacing.large,
            BridgeSpacing.xLarge,
        )

        assertTrue(spacingValues.all { it.value.toInt() % 4 == 0 })
        assertTrue(BridgeBorders.focused > BridgeBorders.subtle)
    }

    @Test
    fun lightAndDarkStatusPalettesKeepMeaningsDistinct() {
        listOf(LightBridgeStatusColors, DarkBridgeStatusColors).forEach { colors ->
            assertNotEquals(colors.success, colors.warning)
            assertNotEquals(colors.info, colors.success)
            assertNotEquals(colors.successContainer, colors.warningContainer)
        }
    }

    @Test
    fun componentShapesFollowOneConsistentScale() {
        assertEquals(RoundedCornerShape(8.dp), BridgeShapes.extraSmall)
        assertEquals(RoundedCornerShape(28.dp), BridgeShapes.extraLarge)
    }
}
