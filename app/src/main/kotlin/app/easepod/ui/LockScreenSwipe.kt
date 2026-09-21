package app.easepod.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

fun Modifier.lockScreenSwipeToUnlock(enabled: Boolean, epoch: Int, onUnlock: () -> Unit): Modifier = composed {
    val unlock by rememberUpdatedState(onUnlock)
    val bottomInset = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    pointerInput(enabled, epoch, bottomInset) {
        if (!enabled) return@pointerInput
        val edgeHeight = 72.dp.toPx()
        val threshold = 72.dp.toPx()
        awaitEachGesture {
            // Observe before child touch guards; claim only an intentional upward drag.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val bottom = size.height - bottomInset
            if (down.position.y < bottom - edgeHeight || down.position.y >= bottom) return@awaitEachGesture
            var dragging = false
            var furthestUp = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) }) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                // Compose represents ACTION_CANCEL as consumed pointer-up changes.
                if (change.isConsumed) return@awaitEachGesture
                val upward = down.position.y - change.position.y
                val sideways = abs(change.position.x - down.position.x)
                val slop = viewConfiguration.touchSlop
                if (upward < -slop || sideways > slop && sideways >= upward || furthestUp - upward > slop) return@awaitEachGesture
                furthestUp = maxOf(furthestUp, upward)
                if (upward > slop && upward > sideways) dragging = true
                if (dragging) change.consume()
                if (!change.pressed) {
                    if (dragging && upward >= threshold) unlock()
                    return@awaitEachGesture
                }
            }
        }
    }
}
