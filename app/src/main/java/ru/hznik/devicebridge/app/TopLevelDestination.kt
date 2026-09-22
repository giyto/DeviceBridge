package ru.hznik.devicebridge.app

import androidx.compose.ui.graphics.vector.ImageVector
import ru.hznik.devicebridge.core.ui.BridgeIcons

internal enum class TopLevelDestination(
    val route: String,
    val label: String,
) {
    Home(
        route = "home",
        label = "Главная",
    ),
    History(
        route = "history",
        label = "История",
    ),
    Settings(
        route = "settings",
        label = "Настройки",
    ),
    ;

    val icon: ImageVector
        get() = when (this) {
            Home -> BridgeIcons.Home
            History -> BridgeIcons.History
            Settings -> BridgeIcons.Settings
        }
}
