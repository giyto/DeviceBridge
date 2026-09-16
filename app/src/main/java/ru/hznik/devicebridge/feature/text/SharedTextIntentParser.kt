package ru.hznik.devicebridge.feature.text

import android.content.Intent

data class SharedTextDraft(
    val requestId: Long,
    val text: String,
)

object SharedTextIntentParser {
    fun parse(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type != "text/plain") return null
        return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()
            ?.takeIf(String::isNotBlank)
    }
}
