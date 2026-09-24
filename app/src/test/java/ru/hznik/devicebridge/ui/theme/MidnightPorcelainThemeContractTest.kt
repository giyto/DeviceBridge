package ru.hznik.devicebridge.ui.theme

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class MidnightPorcelainThemeContractTest {
    @Test
    fun paletteUsesApprovedMidnightAndPorcelainAnchors() {
        val colors = read("src/main/java/ru/hznik/devicebridge/ui/theme/Color.kt")

        listOf(
            "Graphite950 = Color(0xFF080B12)",
            "Graphite900 = Color(0xFF0F1520)",
            "Graphite800 = Color(0xFF171F2E)",
            "Cloud = Color(0xFFF4F6FA)",
            "BridgeBlue = Color(0xFF586BEB)",
            "BridgeBlueLight = Color(0xFF7180FF)",
        ).forEach { token ->
            assertTrue("Missing approved palette token: $token", colors.contains(token))
        }
    }

    @Test
    fun typographyAndShapesUseTheGraphiteContentRhythm() {
        val typography = read("src/main/java/ru/hznik/devicebridge/ui/theme/Type.kt")
        val tokens = read("src/main/java/ru/hznik/devicebridge/ui/theme/DesignTokens.kt")

        listOf("headlineMedium", "titleLarge", "bodyMedium", "labelMedium").forEach { role ->
            assertTrue("Missing typography role: $role", typography.contains("$role = TextStyle("))
        }
        assertTrue(tokens.contains("large = RoundedCornerShape(20.dp)"))
        assertTrue(tokens.contains("extraLarge = RoundedCornerShape(24.dp)"))
    }
    @Test
    fun themeAndFixturesKeepSystemReducedMotionAndAdaptiveContracts() {
        val theme = read("src/main/java/ru/hznik/devicebridge/ui/theme/Theme.kt")
        val componentTests = read(
            "src/androidTest/java/ru/hznik/devicebridge/core/ui/StateComponentsTest.kt",
        )
        val navigationTests = read(
            "src/androidTest/java/ru/hznik/devicebridge/app/DeviceBridgeNavigationTest.kt",
        )

        assertTrue(theme.contains("darkTheme: Boolean = isSystemInDarkTheme()"))
        assertTrue(componentTests.contains("ReducedMotionScale"))
        assertTrue(componentTests.contains("scaleFactor: Float = 0f"))
        assertTrue(navigationTests.contains("LocalNavigationWidthOverride provides width"))
        assertTrue(navigationTests.contains("darkTheme = false"))
        assertTrue(navigationTests.contains("fontScale by mutableStateOf(2f)"))
    }

    private fun read(relativePath: String): String = Files.readString(Path.of(relativePath))
}
