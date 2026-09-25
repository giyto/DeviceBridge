package ru.hznik.devicebridge.core.text

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dayAndMinuteFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

/** Date and time to the minute, "dd.MM.yyyy HH:mm", in the phone's time zone. */
internal fun formatDayAndMinute(epochMillis: Long): String =
    dayAndMinuteFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
