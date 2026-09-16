package ru.hznik.devicebridge.domain.text

sealed interface TextContentValidation {
    data class Valid(val utf8Bytes: Int) : TextContentValidation
    data object Empty : TextContentValidation
    data class TooLarge(val actualUtf8Bytes: Int) : TextContentValidation
}

object TextContentValidator {
    const val MAX_UTF8_BYTES: Int = 100 * 1024

    fun validate(content: String): TextContentValidation {
        if (content.isBlank()) {
            return TextContentValidation.Empty
        }
        val utf8Bytes = content.encodeToByteArray().size
        return if (utf8Bytes <= MAX_UTF8_BYTES) {
            TextContentValidation.Valid(utf8Bytes)
        } else {
            TextContentValidation.TooLarge(utf8Bytes)
        }
    }
}
