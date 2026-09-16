package ru.hznik.devicebridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import ru.hznik.devicebridge.app.DeviceBridgeApp
import ru.hznik.devicebridge.feature.text.SharedTextDraft
import ru.hznik.devicebridge.feature.text.SharedTextIntentParser
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val sharedTextDraft = MutableStateFlow<SharedTextDraft?>(null)
    private var nextSharedTextRequestId = 0L
    private var sharedIntentHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedIntentHandled =
            savedInstanceState?.getBoolean(STATE_SHARED_INTENT_HANDLED) == true
        if (!sharedIntentHandled) {
            acceptSharedText(intent)
        }
        enableEdgeToEdge()
        setContent {
            val pendingSharedDraft by sharedTextDraft.collectAsStateWithLifecycle()
            DeviceBridgeTheme {
                DeviceBridgeApp(
                    sharedTextDraft = pendingSharedDraft,
                    onSharedTextConsumed = { requestId ->
                        if (sharedTextDraft.value?.requestId == requestId) {
                            sharedTextDraft.value = null
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedIntentHandled = false
        acceptSharedText(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_SHARED_INTENT_HANDLED, sharedIntentHandled)
        super.onSaveInstanceState(outState)
    }

    private fun acceptSharedText(source: Intent?) {
        val text = SharedTextIntentParser.parse(source) ?: return
        sharedIntentHandled = true
        sharedTextDraft.value = SharedTextDraft(
            requestId = ++nextSharedTextRequestId,
            text = text,
        )
    }

    private companion object {
        const val STATE_SHARED_INTENT_HANDLED = "shared_text_intent_handled"
    }
}
