package ru.hznik.devicebridge.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

private const val THEME_TRANSITION_MILLIS = 180

/**
 * Switches the theme in a single recomposition and hides the jump behind a fading
 * snapshot of the previous frame. Only the snapshot alpha changes per frame, so the
 * transition costs a redraw instead of recomposing the whole app on every frame.
 */
@Composable
fun ThemeCrossfade(
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (appliedDarkTheme: Boolean) -> Unit,
) {
    val contentLayer = rememberGraphicsLayer()
    var appliedDarkTheme by remember { mutableStateOf(darkTheme) }
    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    val snapshotAlpha = remember { Animatable(0f) }

    LaunchedEffect(darkTheme) {
        if (darkTheme == appliedDarkTheme) return@LaunchedEffect
        snapshot = runCatching { contentLayer.toImageBitmap() }.getOrNull()
        snapshotAlpha.snapTo(1f)
        appliedDarkTheme = darkTheme
        try {
            // Recomposing the app in the new theme is one long frame. Keep the opaque
            // snapshot until that frame is on screen, otherwise it eats the fade time.
            withFrameNanos { }
            withFrameNanos { }
            snapshotAlpha.animateTo(0f, tween(THEME_TRANSITION_MILLIS))
        } finally {
            snapshot = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Recorded only when the app itself redraws, so the fade never re-records it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    contentLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(contentLayer)
                },
        ) {
            content(appliedDarkTheme)
        }
        snapshot?.let { previous ->
            // Alpha is a layer property: each fade frame is a GPU-side update without
            // recomposition or redrawing the snapshot.
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = snapshotAlpha.value },
            ) {
                drawImage(previous)
            }
        }
    }
}
