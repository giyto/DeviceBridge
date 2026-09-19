package ru.hznik.devicebridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BridgeBlueLight,
    onPrimary = Graphite950,
    primaryContainer = BridgeBlueContainerDark,
    onPrimaryContainer = Color(0xFFE4E8FF),
    secondary = Graphite200,
    background = Graphite950,
    onBackground = Graphite100,
    surface = Graphite900,
    onSurface = Graphite100,
    surfaceVariant = Graphite800,
    onSurfaceVariant = Graphite200,
    outline = Graphite400,
    error = DarkBridgeStatusColors.error,
    errorContainer = DarkBridgeStatusColors.errorContainer,
    onErrorContainer = DarkBridgeStatusColors.onErrorContainer,
)

private val LightColorScheme = lightColorScheme(
    primary = BridgeBlue,
    onPrimary = Color.White,
    primaryContainer = BridgeBlueContainer,
    onPrimaryContainer = Color(0xFF293892),
    secondary = Graphite600,
    background = Cloud,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = PorcelainSubtle,
    onSurfaceVariant = Graphite600,
    outline = PorcelainOutline,
    outlineVariant = PorcelainBorder,
    error = LightBridgeStatusColors.error,
    errorContainer = LightBridgeStatusColors.errorContainer,
    onErrorContainer = LightBridgeStatusColors.onErrorContainer,
)

@Composable
fun DeviceBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val statusColors = if (darkTheme) DarkBridgeStatusColors else LightBridgeStatusColors

    CompositionLocalProvider(LocalBridgeStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = BridgeShapes,
            content = content,
        )
    }
}
