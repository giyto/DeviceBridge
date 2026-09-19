package ru.hznik.devicebridge.core.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalFocusManager

@Composable
fun Modifier.dismissKeyboardOnUnconsumedTap(): Modifier {
    val focusManager = LocalFocusManager.current
    return pointerInput(focusManager) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            var consumed = down.isConsumed
            var moved = false
            var pressed = true

            while (pressed) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                consumed = consumed || pointer.isConsumed
                moved = moved || pointer.positionChanged()
                pressed = pointer.pressed
            }

            if (!consumed && !moved) {
                focusManager.clearFocus()
            }
        }
    }
}