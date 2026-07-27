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

/** How far the centroid must travel one way before that counts as a leg of a scrub. */
private const val SCRUB_LEG_PX = 40f

/** Legs after the first: three reversals is a deliberate rub, not an unsteady pan. */
private const val SCRUB_MIN_REVERSALS = 3

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
    // Four fingers held still opens the QuickMenu at their centroid. Four is the only count with a
    // free hold: two and three are taken by hold-to-repeat undo/redo.
    onFourFingerHold: (Offset) -> Unit = {},
    // Three fingers rubbed back and forth clears the layer (Procreate's scrub). Unambiguous against
    // the three-finger tap and hold, which both require the fingers to stay still.
    onThreeFingerScrub: () -> Unit = {},
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
        // Where the fingers are now. A held gesture emits no events, so the hold branch below has
        // no event to read a position from and needs the last one seen.
        var centroid = first.position
        // Scrub tracking: the horizontal direction the centroid is travelling, and how many times
        // it has reversed. A rub is reversals, not distance — a long one-way sweep is a pan.
        var scrubDirection = 0
        var scrubReversals = 0
        var scrubAnchorX = first.position.x
        var scrubFired = false

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
                        // Fires once, not on every tick like the history repeats — and bumping
                        // `repeats` is also what stops the eventual lift from reading as a
                        // four-finger tap and toggling the UI on top of opening the menu.
                        4 -> if (repeats == 0) { onFourFingerHold(centroid); repeats++ }
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
            val down = event.changes.filter { it.pressed }
            if (down.isNotEmpty()) {
                centroid = down.fold(Offset.Zero) { acc, c -> acc + c.position } / down.size.toFloat()

                // Three-finger scrub: count direction reversals of the centroid's horizontal
                // travel. Each leg must clear SCRUB_LEG_PX so a tremor during a three-finger hold
                // can't accumulate into a false clear.
                if (!scrubFired && maxPointers == 3 && pressedNow == 3) {
                    val dx = centroid.x - scrubAnchorX
                    if (kotlin.math.abs(dx) >= SCRUB_LEG_PX) {
                        val dir = if (dx > 0f) 1 else -1
                        if (scrubDirection != 0 && dir != scrubDirection) scrubReversals++
                        scrubDirection = dir
                        scrubAnchorX = centroid.x
                        if (scrubReversals >= SCRUB_MIN_REVERSALS && !gate.shouldSuppressTap()) {
                            onThreeFingerScrub()
                            scrubFired = true
                        }
                    }
                }
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
