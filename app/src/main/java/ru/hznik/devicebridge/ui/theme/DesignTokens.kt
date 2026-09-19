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

object BridgeElevation {
    val flat = 0.dp
    val raised = 2.dp
    val overlay = 6.dp
}

val BridgeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
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
)

internal val LightBridgeStatusColors = BridgeStatusColors(
    info = Color(0xFF2359B8),
    infoContainer = Color(0xFFDCE7FF),
    onInfoContainer = Color(0xFF08224F),
    success = Color(0xFF1B6E46),
    successContainer = Color(0xFFD6F4E4),
    onSuccessContainer = Color(0xFF073A22),
    warning = Color(0xFF8A5700),
    warningContainer = Color(0xFFFFE5B2),
    onWarningContainer = Color(0xFF3F2700),
)

internal val DarkBridgeStatusColors = BridgeStatusColors(
    info = Color(0xFF9DBBFF),
    infoContainer = Color(0xFF17386F),
    onInfoContainer = Color(0xFFDCE7FF),
    success = Color(0xFF72D6A4),
    successContainer = Color(0xFF123F2B),
    onSuccessContainer = Color(0xFFD6F4E4),
    warning = Color(0xFFFFC96B),
    warningContainer = Color(0xFF4C350C),
    onWarningContainer = Color(0xFFFFE5B2),
)

internal val LocalBridgeStatusColors = staticCompositionLocalOf {
    LightBridgeStatusColors
}

val MaterialTheme.bridgeStatusColors: BridgeStatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalBridgeStatusColors.current
