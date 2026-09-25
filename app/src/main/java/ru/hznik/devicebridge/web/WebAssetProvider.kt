package ru.hznik.devicebridge.web

import java.io.InputStream
import java.util.Locale

interface WebAssetProvider {
    fun find(requestPath: String): WebAssetResource?
}

interface WebAssetSource {
    fun describe(path: String): WebAssetDescriptor?
}

data class WebAssetDescriptor(
    val length: Long,
    val openStream: () -> InputStream,
)

class WebAssetResource internal constructor(
    val contentType: String,
    val length: Long,
    private val streamFactory: () -> InputStream,
) {
    fun openStream(): InputStream = streamFactory()
}

class AllowlistedWebAssetProvider(
    allowedPaths: Set<String>,
    private val source: WebAssetSource,
) : WebAssetProvider {

    private val allowedPaths = allowedPaths
        .onEach { require(isSafeInternalPath(it)) { "Unsafe allowlisted web asset: $it" } }
        .toSet()

    override fun find(requestPath: String): WebAssetResource? {
        val path = normalizeRequestPath(requestPath) ?: return null
        if (path !in allowedPaths) return null

        val descriptor = source.describe(path) ?: return null
        if (descriptor.length < 0L) return null

        return WebAssetResource(
            contentType = contentTypeFor(path),
            length = descriptor.length,
            streamFactory = descriptor.openStream,
        )
    }

    private fun normalizeRequestPath(requestPath: String): String? {
        if (requestPath == "/") return INDEX_PATH
        if (
            !requestPath.startsWith("/") ||
            requestPath.startsWith("//") ||
            requestPath.any { it == '\\' || it == '%' || it == '?' || it == '#' || it == '\u0000' }
        ) {
            return null
        }

        val path = requestPath.removePrefix("/")
        return path.takeIf(::isSafeInternalPath)
    }

    private fun isSafeInternalPath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || path.endsWith("/")) return false
        return path.split('/').none { segment ->
            segment.isEmpty() || segment == "." || segment == ".."
        }
    }

    private fun contentTypeFor(path: String): String = when (
        path.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    ) {
        "html" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    private companion object {
        const val INDEX_PATH = "index.html"
    }
}
