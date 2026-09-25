package ru.hznik.devicebridge.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object BridgeSpacing {
    val xSmall = 4.dp
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
    val xLarge = 32.dp
}

object BridgeBorders {
    val subtle = 1.dp
    val focused = 3.dp
}

val BridgeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Immutable
data class BridgeStatusColors(
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
)

internal val LightBridgeStatusColors = BridgeStatusColors(
    info = Color(0xFF168EBA),
    infoContainer = Color(0xFFDDF3FA),
    onInfoContainer = Color(0xFF0B506A),
    success = Color(0xFF168560),
    successContainer = Color(0xFFDDF4EA),
    onSuccessContainer = Color(0xFF0B4A36),
    warning = Color(0xFFA86B16),
    warningContainer = Color(0xFFFFF0D2),
    onWarningContainer = Color(0xFF5F3A08),
    error = Color(0xFFCF4A5A),
    errorContainer = Color(0xFFFDE7EA),
    onErrorContainer = Color(0xFF741E2B),
)

internal val DarkBridgeStatusColors = BridgeStatusColors(
    info = Color(0xFF49CFF5),
    infoContainer = Color(0xFF123B4A),
    onInfoContainer = Color(0xFFC8F3FF),
    success = Color(0xFF45D39D),
    successContainer = Color(0xFF123C31),
    onSuccessContainer = Color(0xFFCFF9E9),
    warning = Color(0xFFE8B45B),
    warningContainer = Color(0xFF463516),
    onWarningContainer = Color(0xFFFFEBC3),
    error = Color(0xFFF07178),
    errorContainer = Color(0xFF472127),
    onErrorContainer = Color(0xFFFFD9DC),
)

internal val LocalBridgeStatusColors = staticCompositionLocalOf {
    LightBridgeStatusColors
}

val MaterialTheme.bridgeStatusColors: BridgeStatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalBridgeStatusColors.current
