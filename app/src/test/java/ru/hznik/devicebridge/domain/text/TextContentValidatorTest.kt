package ru.hznik.devicebridge.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextContentValidatorTest {

    @Test
    fun acceptsAsciiCyrillicAndEmojiUsingUtf8ByteCount() {
        val ascii = TextContentValidator.validate("hello")
        val cyrillic = TextContentValidator.validate("Привет")
        val emoji = TextContentValidator.validate("🙂")

        assertEquals(TextContentValidation.Valid(5), ascii)
        assertEquals(TextContentValidation.Valid(12), cyrillic)
        assertEquals(TextContentValidation.Valid(4), emoji)
    }

    @Test
    fun acceptsExactlyOneHundredKilobytes() {
        val content = "a".repeat(TextContentValidator.MAX_UTF8_BYTES)

        assertEquals(
            TextContentValidation.Valid(TextContentValidator.MAX_UTF8_BYTES),
            TextContentValidator.validate(content),
        )
    }

    @Test
    fun rejectsContentOverOneHundredKilobytes() {
        val content = "🙂".repeat((TextContentValidator.MAX_UTF8_BYTES / 4) + 1)

        val result = TextContentValidator.validate(content)

        assertTrue(result is TextContentValidation.TooLarge)
        assertEquals(
            TextContentValidator.MAX_UTF8_BYTES + 4,
            (result as TextContentValidation.TooLarge).actualUtf8Bytes,
        )
    }

    @Test
    fun rejectsEmptyAndWhitespaceOnlyContent() {
        assertEquals(TextContentValidation.Empty, TextContentValidator.validate(""))
        assertEquals(TextContentValidation.Empty, TextContentValidator.validate("  \n\t"))
    }
}
