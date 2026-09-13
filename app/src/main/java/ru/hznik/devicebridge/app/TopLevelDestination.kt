package ru.hznik.devicebridge.app

internal enum class TopLevelDestination(
    val route: String,
    val label: String,
    val symbol: String,
) {
    Home(
        route = "home",
        label = "Главная",
        symbol = "●",
    ),
    History(
        route = "history",
        label = "История",
        symbol = "↺",
    ),
    Settings(
        route = "settings",
        label = "Настройки",
        symbol = "⚙",
    ),
}
