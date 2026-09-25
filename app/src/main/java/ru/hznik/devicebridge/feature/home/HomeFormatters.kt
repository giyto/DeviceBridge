package ru.hznik.devicebridge.feature.home

import ru.hznik.devicebridge.core.text.ruPlural

internal fun formatIdleMinutes(minutes: Int): String =
    "$minutes ${ruPlural(minutes, "минута", "минуты", "минут")}"

internal fun formatUptime(totalSeconds: Long): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0)
    val hours = safeSeconds / 3_600
    val minutes = (safeSeconds % 3_600) / 60
    val seconds = safeSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

internal fun formatCountdown(totalSeconds: Long): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0)
    val minutes = safeSeconds / 60
    val seconds = safeSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
