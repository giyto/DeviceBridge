package ru.hznik.devicebridge.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTextPreviewTest {
    @Test
    fun keepsAtMostTwoHundredUnicodeCodePointsWithoutSplittingEmoji() {
        val input = "a".repeat(199) + "🙂" + "tail"

        val preview = createHistoryTextPreview(input)

        assertEquals(200, preview.codePointCount(0, preview.length))
        assertTrue(preview.endsWith("🙂"))
    }

    @Test
    fun keepsMarkupAndMultilineContentAsPlainText() {
        val input = "<script>alert('x')</script>\nsecond line"

        assertEquals(input, createHistoryTextPreview(input))
    }

    @Test
    fun boundsVeryLargeInput() {
        val input = "аб🙂\n".repeat(25_000)

        val preview = createHistoryTextPreview(input)

        assertEquals(200, preview.codePointCount(0, preview.length))
        assertTrue(preview.length < input.length)
    }
}
