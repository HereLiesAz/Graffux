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
private const val REPEAT_START_MS = 450L
private const val REPEAT_TICK_MS = 130L

/**
 * Procreate's signature history gestures: **two-finger tap = undo, three-finger tap = redo**,
 * **hold two/three fingers to repeat** (rapid step-back through history), and **four-finger tap**
 * to toggle the full-screen (UI-hidden) view.
 *
 * A pure observer on [PointerEventPass.Initial]: it never consumes anything, so it coexists with
 * every other handler (drawing, pan/zoom, taps) — a qualifying tap is short, still, and lands
 * 2–4 fingers; anything travelling is someone else's gesture.
 */
fun Modifier.multiFingerTaps(
    gate: StrokeGate,
    onTwoFingerTap: () -> Unit,
    onThreeFingerTap: () -> Unit,
    onFourFingerTap: () -> Unit = {},
): Modifier = pointerInput(gate) {
    val slop = viewConfiguration.touchSlop * 1.5f
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val startMs = first.uptimeMillis
        val origins = HashMap<PointerId, Offset>().apply { put(first.id, first.position) }
        var maxPointers = 1
        var pressedNow = 1
        var moved = false
        var repeats = 0

        while (true) {
            // Timed wait: fingers held perfectly still emit no events, and the timeout drives
            // Procreate's hold-to-repeat (keep two fingers down to step rapidly back through
            // history). withTimeoutOrNull here is AwaitPointerEventScope's own member.
            val event = withTimeoutOrNull(REPEAT_TICK_MS) { awaitPointerEvent(PointerEventPass.Initial) }

            if (event == null) {
                val heldLongEnough = SystemClock.uptimeMillis() - startMs >= REPEAT_START_MS
                if (heldLongEnough && !moved && pressedNow == maxPointers && !gate.shouldSuppressTap()) {
                    when (maxPointers) {
                        2 -> { onTwoFingerTap(); repeats++ }
                        3 -> { onThreeFingerTap(); repeats++ }
                    }
                }
                continue
            }

            pressedNow = event.changes.count { it.pressed }
            if (pressedNow > maxPointers) maxPointers = pressedNow
            for (change in event.changes) {
                val origin = origins.getOrPut(change.id) { change.position }
                if ((change.position - origin).getDistance() > slop) moved = true
            }
            if (pressedNow == 0) {
                val duration = event.changes.maxOf { it.uptimeMillis } - startMs
                if (repeats == 0 && !moved && duration <= TAP_MAX_DURATION_MS && !gate.shouldSuppressTap()) {
                    when (maxPointers) {
                        2 -> onTwoFingerTap()
                        3 -> onThreeFingerTap()
                        4 -> onFourFingerTap()
                    }
                }
                break
            }
        }
    }
}
