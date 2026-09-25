package ru.hznik.devicebridge.domain.settings

private val repeatedWhitespace = Regex("\\s+")

fun androidDeviceName(manufacturer: String?, model: String?): String {
    val normalizedModel = normalizeBuildLabel(model)
        .takeUnless { it.equals("unknown", ignoreCase = true) }
        ?: return SettingsDefaults.DEFAULT_DEVICE_NAME
    val normalizedManufacturer = normalizeBuildLabel(manufacturer)
        ?.takeUnless { it.equals("unknown", ignoreCase = true) }

    val combined = if (
        normalizedManufacturer.isNullOrBlank() ||
        normalizedModel.startsWith(normalizedManufacturer, ignoreCase = true)
    ) {
        normalizedModel
    } else {
        "$normalizedManufacturer $normalizedModel"
    }
    return combined.takeCodePoints(MAX_DEVICE_NAME_CODE_POINTS)
        .trim()
        .ifBlank { SettingsDefaults.DEFAULT_DEVICE_NAME }
}

private fun normalizeBuildLabel(value: String?): String? =
    value
        ?.map { character -> if (character.isISOControl()) ' ' else character }
        ?.joinToString(separator = "")
        ?.trim()
        ?.replace(repeatedWhitespace, " ")
        ?.takeIf(String::isNotBlank)

private fun String.takeCodePoints(maxCodePoints: Int): String {
    val count = codePointCount(0, length)
    if (count <= maxCodePoints) return this
    return substring(0, offsetByCodePoints(0, maxCodePoints))
}
