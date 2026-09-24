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
import ru.hznik.devicebridge.app.OpenSectionRequest
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
import ru.hznik.devicebridge.server.ServerStartRequests

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
    @Inject
    lateinit var serverStartRequests: ServerStartRequests

    private val sharedTextDraft = MutableStateFlow<SharedTextDraft?>(null)
    private val sharedFileDraft = MutableStateFlow<SharedFileDraft?>(null)
    private val openSectionRequest = MutableStateFlow<OpenSectionRequest?>(null)
    private var nextSharedTextRequestId = 0L
    private var sharedIntentHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedIntentHandled =
            savedInstanceState?.getBoolean(STATE_SHARED_INTENT_HANDLED) == true
        if (!sharedIntentHandled) {
            acceptSharedIntent(intent)
        }
        if (savedInstanceState == null) {
            acceptTileStartIntent(intent)
            acceptOpenSectionIntent(intent)
        }
        enableEdgeToEdge()
        setContent {
            val pendingSharedDraft by sharedTextDraft.collectAsStateWithLifecycle()
            val pendingSharedFileDraft by sharedFileDraft.collectAsStateWithLifecycle()
            val pendingServerStart by serverStartRequests.pending.collectAsStateWithLifecycle()
            val pendingOpenSection by openSectionRequest.collectAsStateWithLifecycle()
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
                        startServerRequest = pendingServerStart,
                        openSectionRequest = pendingOpenSection,
                        onOpenSectionConsumed = { requestId ->
                            if (openSectionRequest.value?.id == requestId) {
                                openSectionRequest.value = null
                            }
                        },
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
        acceptTileStartIntent(intent)
        acceptOpenSectionIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_SHARED_INTENT_HANDLED, sharedIntentHandled)
        super.onSaveInstanceState(outState)
    }

    /** The Quick Settings tile opens the app when it cannot start the server by itself. */
    private fun acceptTileStartIntent(source: Intent?) {
        if (source?.getBooleanExtra(EXTRA_START_SERVER_FROM_TILE, false) != true) return
        source.removeExtra(EXTRA_START_SERVER_FROM_TILE)
        serverStartRequests.request()
    }

    /** An event notification opens the screen that shows its event. */
    private fun acceptOpenSectionIntent(source: Intent?) {
        val section = source?.getStringExtra(EXTRA_OPEN_SECTION) ?: return
        source.removeExtra(EXTRA_OPEN_SECTION)
        val notificationId = source.getIntExtra(EXTRA_DISMISS_NOTIFICATION, NO_NOTIFICATION)
        source.removeExtra(EXTRA_DISMISS_NOTIFICATION)
        if (notificationId != NO_NOTIFICATION) {
            getSystemService(android.app.NotificationManager::class.java)?.cancel(notificationId)
        }
        openSectionRequest.value = OpenSectionRequest(++nextSharedTextRequestId, section)
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

    companion object {
        const val EXTRA_START_SERVER_FROM_TILE = "ru.hznik.devicebridge.extra.START_SERVER_FROM_TILE"
        const val EXTRA_OPEN_SECTION = "ru.hznik.devicebridge.extra.OPEN_SECTION"
        const val EXTRA_DISMISS_NOTIFICATION = "ru.hznik.devicebridge.extra.DISMISS_NOTIFICATION"
        private const val NO_NOTIFICATION = -1
        private const val STATE_SHARED_INTENT_HANDLED = "shared_text_intent_handled"
        private val LIGHT_NAVIGATION_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_NAVIGATION_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
