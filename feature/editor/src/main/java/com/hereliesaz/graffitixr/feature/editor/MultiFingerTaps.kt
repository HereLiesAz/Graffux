// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/MultiFingerTaps.kt
package com.hereliesaz.graffitixr.feature.editor

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Coordination between the drawing surface and the multi-finger tap observer: a two-finger tap
 * that CANCELLED an in-flight stroke already did its job (the stroke is gone) and must not also
 * fire an undo of the previous action.
 */
class StrokeGate {
    /** True while a stroke is being painted. */
    var strokeActive: Boolean = false

    private var lastCancelMs: Long = 0L

    fun markCancelled() {
        strokeActive = false
        lastCancelMs = SystemClock.uptimeMillis()
    }

    /** True when a just-finished multi-finger gesture should be swallowed. */
    fun shouldSuppressTap(): Boolean =
        strokeActive || SystemClock.uptimeMillis() - lastCancelMs < 600L
}

private const val TAP_MAX_DURATION_MS = 350L

/**
 * Procreate's signature history gestures: **two-finger tap = undo, three-finger tap = redo.**
 *
 * A pure observer on [PointerEventPass.Initial]: it never consumes anything, so it coexists with
 * every other handler (drawing, pan/zoom, taps) — a qualifying tap is short, still, and lands
 * 2 or 3 fingers; anything longer or travelling is someone else's gesture.
 */
fun Modifier.multiFingerTaps(
    gate: StrokeGate,
    onTwoFingerTap: () -> Unit,
    onThreeFingerTap: () -> Unit,
): Modifier = pointerInput(gate) {
    val slop = viewConfiguration.touchSlop * 1.5f
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val startMs = first.uptimeMillis
        val origins = HashMap<PointerId, Offset>().apply { put(first.id, first.position) }
        var maxPointers = 1
        var moved = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount > maxPointers) maxPointers = pressedCount
            for (change in event.changes) {
                val origin = origins.getOrPut(change.id) { change.position }
                if ((change.position - origin).getDistance() > slop) moved = true
            }
            if (pressedCount == 0) {
                val duration = event.changes.maxOf { it.uptimeMillis } - startMs
                if (!moved && duration <= TAP_MAX_DURATION_MS && !gate.shouldSuppressTap()) {
                    when (maxPointers) {
                        2 -> onTwoFingerTap()
                        3 -> onThreeFingerTap()
                    }
                }
                break
            }
        }
    }
}
