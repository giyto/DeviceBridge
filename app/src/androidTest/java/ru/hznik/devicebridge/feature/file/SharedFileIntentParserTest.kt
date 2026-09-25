package ru.hznik.devicebridge.feature.file

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.feature.text.SharedTextIntentParser

@RunWith(AndroidJUnit4::class)
class SharedFileIntentParserTest {

    @Test
    fun actionSendCreatesUnconfirmedSingleFilePreview() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://provider/image"))

        val draft = SharedFileIntentParser.parse(intent)

        assertEquals(listOf("content://provider/image"), draft?.items?.map { it.uri })
    }

    @Test
    fun actionSendMultipleKeepsValidAndInvalidItemsWithoutCrashingPreview() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("application/octet-stream")
            .putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(
                    Uri.parse("content://provider/valid"),
                    Uri.parse("file:///private/invalid"),
                ),
            )

        val draft = SharedFileIntentParser.parse(intent)

        assertEquals(2, draft?.items?.size)
        assertNull(draft?.items?.first()?.error)
        assertEquals(SharedFileIntentError.UNSUPPORTED_URI, draft?.items?.last()?.error)
    }

    @Test
    fun textPlainFlowRemainsOwnedByExistingParser() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Существующий текст")

        assertNull(SharedFileIntentParser.parse(intent))
        assertEquals("Существующий текст", SharedTextIntentParser.parse(intent))
    }
}
