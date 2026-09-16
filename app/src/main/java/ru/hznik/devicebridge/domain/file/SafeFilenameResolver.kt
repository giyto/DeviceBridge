package ru.hznik.devicebridge.domain.file

import java.util.Locale

const val MAX_SAFE_FILENAME_LENGTH = 120

private const val MAX_SAFE_EXTENSION_LENGTH = 16
private val BIDI_CONTROL_RANGES = listOf(
    '\u202A'..'\u202E',
    '\u2066'..'\u2069',
)
private val WINDOWS_RESERVED_NAMES = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    (1..9).forEach { index ->
        add("COM" + index)
        add("LPT" + index)
    }
}

object SafeFilenameResolver {

    fun normalize(
        rawName: String,
        fallbackId: String,
    ): String {
        val leaf = rawName.replace('\\', '/').substringAfterLast('/')
        val cleaned = leaf
            .filterNot { it.isISOControl() || it.isBidiControl() }
            .trim()
            .trim('.')
        val (rawBase, extension) = splitSafeExtension(cleaned)
        val fallback = "file-" + fallbackId.toSafeFallbackId()
        val base = rawBase
            .trim()
            .trim('.')
            .takeUnless { it.isBlank() || it.isWindowsReserved() }
            ?: fallback
        val availableBaseLength = (MAX_SAFE_FILENAME_LENGTH - extension.length).coerceAtLeast(1)
        val limitedBase = base.take(availableBaseLength).trimEnd(' ', '.').ifBlank { fallback }
        return (limitedBase.take(availableBaseLength) + extension)
            .take(MAX_SAFE_FILENAME_LENGTH)
    }

    fun resolveCollision(
        normalizedName: String,
        existingNames: Set<String>,
    ): String {
        val existing = existingNames.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        if (normalizedName.lowercase(Locale.ROOT) !in existing) return normalizedName

        val (base, extension) = splitSafeExtension(normalizedName)
        var index = 1
        while (true) {
            val suffix = " (" + index + ")"
            val availableBaseLength =
                (MAX_SAFE_FILENAME_LENGTH - extension.length - suffix.length).coerceAtLeast(1)
            val candidate =
                base.take(availableBaseLength).trimEnd(' ', '.') + suffix + extension
            if (candidate.lowercase(Locale.ROOT) !in existing) return candidate
            index += 1
        }
    }

    private fun splitSafeExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return name to ""
        val candidate = name.substring(dot + 1)
        if (
            candidate.length > MAX_SAFE_EXTENSION_LENGTH ||
            candidate.any { !it.isLetterOrDigit() }
        ) {
            return name to ""
        }
        return name.substring(0, dot) to ("." + candidate.lowercase(Locale.ROOT))
    }

    private fun String.toSafeFallbackId(): String =
        filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(16)
            .ifBlank { "unknown" }

    private fun String.isWindowsReserved(): Boolean =
        uppercase(Locale.ROOT) in WINDOWS_RESERVED_NAMES

    private fun Char.isBidiControl(): Boolean =
        this == '\u200E' ||
            this == '\u200F' ||
            BIDI_CONTROL_RANGES.any { this in it }
}
