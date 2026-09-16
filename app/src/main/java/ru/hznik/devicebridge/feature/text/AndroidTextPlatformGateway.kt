package ru.hznik.devicebridge.feature.text

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import ru.hznik.devicebridge.domain.text.TextContentClassifier
import ru.hznik.devicebridge.domain.text.TextContentKind

class AndroidTextPlatformGateway(
    private val context: Context,
) {
    suspend fun readClipboardText(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? ClipboardManager
            ?: return null
        if (!clipboard.hasPrimaryClip()) return null
        return clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.takeIf(String::isNotBlank)
    }

    fun openHttpLink(content: String): Boolean {
        if (TextContentClassifier.classify(content) != TextContentKind.LINK) {
            return false
        }
        val uri = Uri.parse(content)
        val scheme = uri.scheme?.lowercase()
        if (scheme !in ALLOWED_SCHEMES || uri.host.isNullOrBlank()) {
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private companion object {
        val ALLOWED_SCHEMES = setOf("http", "https")
    }
}
