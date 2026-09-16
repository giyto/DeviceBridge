package ru.hznik.devicebridge.domain.text

import java.net.URI

object TextContentClassifier {
    fun classify(content: String): TextContentKind {
        val uri = runCatching { URI(content) }.getOrNull() ?: return TextContentKind.TEXT
        val scheme = uri.scheme?.lowercase() ?: return TextContentKind.TEXT
        return if (scheme in allowedSchemes && !uri.host.isNullOrBlank()) {
            TextContentKind.LINK
        } else {
            TextContentKind.TEXT
        }
    }

    private val allowedSchemes = setOf("http", "https")
}
