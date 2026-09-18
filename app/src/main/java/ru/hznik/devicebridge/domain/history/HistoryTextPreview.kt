package ru.hznik.devicebridge.domain.history

const val MAX_HISTORY_TEXT_PREVIEW_CODE_POINTS = 200

fun createHistoryTextPreview(value: String): String {
    val codePointCount = value.codePointCount(0, value.length)
    if (codePointCount <= MAX_HISTORY_TEXT_PREVIEW_CODE_POINTS) {
        return value
    }
    val endIndex = value.offsetByCodePoints(
        0,
        MAX_HISTORY_TEXT_PREVIEW_CODE_POINTS,
    )
    return value.substring(0, endIndex)
}
