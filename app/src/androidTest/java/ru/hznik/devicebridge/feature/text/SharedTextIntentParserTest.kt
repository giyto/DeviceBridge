package ru.hznik.devicebridge.feature.text

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedTextIntentParserTest {
    @Test
    fun acceptsOnlyNonBlankTextPlainPayload() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Привет из меню Поделиться")

        assertEquals(
            "Привет из меню Поделиться",
            SharedTextIntentParser.parse(intent),
        )
    }

    @Test
    fun rejectsUnsupportedMimeMissingPayloadAndWhitespace() {
        assertNull(
            SharedTextIntentParser.parse(
                Intent(Intent.ACTION_SEND)
                    .setType("image/png")
                    .putExtra(Intent.EXTRA_TEXT, "not text/plain"),
            ),
        )
        assertNull(
            SharedTextIntentParser.parse(
                Intent(Intent.ACTION_SEND).setType("text/plain"),
            ),
        )
        assertNull(
            SharedTextIntentParser.parse(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "   \n"),
            ),
        )
        assertNull(
            SharedTextIntentParser.parse(
                Intent(Intent.ACTION_VIEW)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "wrong action"),
            ),
        )
    }
}
