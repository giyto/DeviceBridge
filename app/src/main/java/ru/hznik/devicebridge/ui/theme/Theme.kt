package ru.hznik.devicebridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BridgeBlueLight,
    onPrimary = Graphite950,
    primaryContainer = BridgeBlueContainerDark,
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Graphite200,
    background = Graphite950,
    onBackground = Graphite100,
    surface = Graphite900,
    onSurface = Graphite100,
    surfaceVariant = Graphite800,
    onSurfaceVariant = Graphite200,
    outline = Graphite600,
)

private val LightColorScheme = lightColorScheme(
    primary = BridgeBlue,
    onPrimary = Color.White,
    primaryContainer = BridgeBlueContainer,
    onPrimaryContainer = Color(0xFF08224F),
    secondary = Graphite600,
    background = Cloud,
    onBackground = Graphite950,
    surface = Color.White,
    onSurface = Graphite950,
    surfaceVariant = Graphite100,
    onSurfaceVariant = Graphite600,
    outline = Color(0xFF8995A5),
)

@Composable
fun DeviceBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Kept in the API for previews and future settings. DeviceBridge currently
    // uses its own accessible blue/graphite palette in both system themes.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
