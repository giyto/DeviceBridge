package ru.hznik.devicebridge

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import ru.hznik.devicebridge.app.DeviceBridgeApp
import ru.hznik.devicebridge.feature.text.SharedTextDraft
import ru.hznik.devicebridge.feature.text.SharedTextIntentParser
import ru.hznik.devicebridge.feature.file.SharedFileDraft
import ru.hznik.devicebridge.feature.file.SharedFileIntentParser
import ru.hznik.devicebridge.domain.repository.ThemePreferenceRepository
import ru.hznik.devicebridge.domain.settings.ThemePreference
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme
import ru.hznik.devicebridge.ui.theme.ThemeCrossfade
import javax.inject.Inject
import ru.hznik.devicebridge.data.file.CompletedFileRegistry
import ru.hznik.devicebridge.data.file.FileDestinationLeaseRegistry
import ru.hznik.devicebridge.data.file.FileSourceRegistry

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var completedFileRegistry: CompletedFileRegistry
    @Inject
    lateinit var destinationLeaseRegistry: FileDestinationLeaseRegistry
    @Inject
    lateinit var fileSourceRegistry: FileSourceRegistry
    @Inject
    lateinit var themePreferenceRepository: ThemePreferenceRepository

    private val sharedTextDraft = MutableStateFlow<SharedTextDraft?>(null)
    private val sharedFileDraft = MutableStateFlow<SharedFileDraft?>(null)
    private var nextSharedTextRequestId = 0L
    private var sharedIntentHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedIntentHandled =
            savedInstanceState?.getBoolean(STATE_SHARED_INTENT_HANDLED) == true
        if (!sharedIntentHandled) {
            acceptSharedIntent(intent)
        }
        enableEdgeToEdge()
        setContent {
            val pendingSharedDraft by sharedTextDraft.collectAsStateWithLifecycle()
            val pendingSharedFileDraft by sharedFileDraft.collectAsStateWithLifecycle()
            val themePreference by themePreferenceRepository.themePreference
                .collectAsStateWithLifecycle(initialValue = null)
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (themePreference) {
                null -> systemDarkTheme
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        LIGHT_NAVIGATION_SCRIM,
                        DARK_NAVIGATION_SCRIM,
                    ) { darkTheme },
                )
            }
            ThemeCrossfade(darkTheme = darkTheme) { appliedDarkTheme ->
                DeviceBridgeTheme(darkTheme = appliedDarkTheme) {
                    DeviceBridgeApp(
                        completedFileRegistry = completedFileRegistry,
                        destinationLeaseRegistry = destinationLeaseRegistry,
                        fileSourceRegistry = fileSourceRegistry,
                        sharedTextDraft = pendingSharedDraft,
                        sharedFileDraft = pendingSharedFileDraft,
                        onSharedTextConsumed = { requestId ->
                            if (sharedTextDraft.value?.requestId == requestId) {
                                sharedTextDraft.value = null
                            }
                        },
                        onSharedFileConsumed = { requestId ->
                            if (sharedFileDraft.value?.requestId == requestId) {
                                sharedFileDraft.value = null
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedIntentHandled = false
        acceptSharedIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_SHARED_INTENT_HANDLED, sharedIntentHandled)
        super.onSaveInstanceState(outState)
    }

    private fun acceptSharedIntent(source: Intent?) {
        val text = SharedTextIntentParser.parse(source)
        if (text != null) {
            sharedIntentHandled = true
            sharedTextDraft.value = SharedTextDraft(
                requestId = ++nextSharedTextRequestId,
                text = text,
            )
            return
        }
        val files = SharedFileIntentParser.parse(source) ?: return
        sharedIntentHandled = true
        sharedFileDraft.value = files.copy(
            requestId = ++nextSharedTextRequestId,
        )
    }

    private companion object {
        const val STATE_SHARED_INTENT_HANDLED = "shared_text_intent_handled"
        val LIGHT_NAVIGATION_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_NAVIGATION_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
