package ru.hznik.devicebridge.feature.file

import android.content.Intent
import android.net.Uri
import android.os.Build

enum class SharedFileIntentError {
    UNSUPPORTED_URI,
}

data class SharedFileDraftItem(
    val uri: String,
    val error: SharedFileIntentError?,
)

data class SharedFileDraft(
    val items: List<SharedFileDraftItem>,
    val requestId: Long = 0,
)

object SharedFileIntentParser {
    fun parse(intent: Intent?): SharedFileDraft? {
        if (intent == null) return null
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) {
            return null
        }
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.singleStream())
            Intent.ACTION_SEND_MULTIPLE -> intent.multipleStreams()
            else -> emptyList()
        } + intent.clipDataUris()
        val distinctUris = uris.distinctBy(Uri::toString)
        if (distinctUris.isEmpty()) return null
        return SharedFileDraft(
            items = distinctUris.map { uri ->
                SharedFileDraftItem(
                    uri = uri.toString(),
                    error = if (uri.scheme == ContentScheme) {
                        null
                    } else {
                        SharedFileIntentError.UNSUPPORTED_URI
                    },
                )
            },
        )
    }

    private const val ContentScheme = "content"
}

private fun Intent.clipDataUris(): List<Uri> {
    val data = clipData ?: return emptyList()
    return buildList(data.itemCount) {
        repeat(data.itemCount) { index ->
            data.getItemAt(index).uri?.let(::add)
        }
    }
}

@Suppress("DEPRECATION")
private fun Intent.singleStream(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM)
    }

@Suppress("DEPRECATION")
private fun Intent.multipleStreams(): List<Uri> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
        getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    }
