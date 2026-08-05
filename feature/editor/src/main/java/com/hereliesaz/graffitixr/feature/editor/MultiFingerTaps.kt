// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/MultiFingerTaps.kt
package com.hereliesaz.graffitixr.feature.editor

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import com.hereliesaz.graffitixr.common.model.GestureAction
import com.hereliesaz.graffitixr.common.model.GestureSlot
import kotlin.math.abs

/**
 * Coordination between the drawing surfaces and the multi-finger gesture observer: a two-finger tap
 * that CANCELLED an in-flight stroke already did its job (the stroke is gone) and must not also
 * fire an undo of the previous action.
 *
 * Everything here is edge-triggered rather than timed. The previous version latched a plain
 * `strokeActive` boolean and additionally suppressed for 600 ms after a cancel, which gave it two
 * ways to silently kill every multi-finger gesture in the app:
 *
 *  * the latch was cleared only by the drawing surface's own gesture loop reaching its lift/cancel
 *    branch, so a surface torn down mid-stroke (tool change, layer lock, selection change, project
 *    reload, leaving composition) left it stuck `true` **forever** — after which no two-, three- or
 *    four-finger gesture ever fired again, in any mode, whatever was active or selected;
 *  * the 600 ms window outlived the gesture that opened it, so the *next* tap was eaten too — you
 *    could not undo twice in a row at speed.
 *
 * Now the latch is cleared at the start of every fresh gesture (a first-down proves every finger was
 * up an instant ago, so no stroke can still be in flight), and a cancel is scoped to the exact
 * gesture that caused it through [cancelCount].
 */
class StrokeGate {
    /** True while a stroke is being painted. */
    var strokeActive: Boolean = false

    /**
     * Bumped every time an in-flight stroke is thrown away because a second finger landed. The
     * observer snapshots this when a gesture begins and compares it when the gesture would fire, so
     * only the gesture that did the cancelling is swallowed.
     */
    var cancelCount: Long = 0L
        private set

    fun markCancelled() {
        strokeActive = false
        cancelCount++
    }

    /**
     * Called by the observer the instant a brand-new gesture begins. Every finger was up a moment
     * ago, so nothing can legitimately still be mid-stroke: a latch left standing here is a leak
     * from a drawing surface disposed mid-stroke, and clearing it is what stops a single torn-down
     * stroke from disabling the gestures for the rest of the session.
     */
    fun onGestureStart() {
        strokeActive = false
    }
}

/** How far the centroid must travel one way before that counts as a leg of a scrub. */
private const val SCRUB_LEG_PX = 40f

/** Legs after the first: three reversals is a deliberate rub, not an unsteady pan. */
private const val SCRUB_MIN_REVERSALS = 3

/**
 * Anything shorter than the hold threshold is a tap. These used to be 350 ms and 450 ms, which left
 * a 100 ms dead zone in which a deliberate two-finger tap did nothing at all — too long to register
 * as a tap, too short to start the hold repeat.
 */
internal const val TAP_MAX_DURATION_MS = 250L
internal const val REPEAT_START_MS = 250L
internal const val REPEAT_TICK_MS = 80L

/** A pointer as [MultiFingerRecognizer] sees it — everything it needs from a `PointerInputChange`. */
data class TouchPoint(val id: PointerId, val position: Offset)

/**
 * The state machine behind [multiFingerTaps], kept free of Compose and Android so it can be tested
 * directly. Feed it the pointers that are down; it answers with the [GestureSlot] to fire, if any.
 *
 * The rules, all of which exist to keep a gesture from being rejected for something the user did not
 * do:
 *
 *  * **Stillness is measured from the moment the gesture is assembled**, not from the first finger's
 *    down. Fingers never land in the same millisecond, and the first one's drift while the others
 *    are still on their way down is not a pan — charging it against the tap is what made two- and
 *    three-finger taps miss.
 *  * **Only fingers that are still down count towards movement.** A lifting pointer routinely
 *    reports a position jump on its up-event, which used to disqualify an otherwise perfect tap.
 *  * **The overall duration is still measured from the first down**, so a long pan that later
 *    acquires another finger cannot end as a tap.
 */
class MultiFingerRecognizer(private val slopPx: Float) {
    private var startMs = 0L
    private val origins = HashMap<PointerId, Offset>()
    private var maxPointers = 0
    private var pressedNow = 0
    private var moved = false
    private var repeats = 0
    private var scrubDirection = 0
    private var scrubReversals = 0
    private var scrubAnchorX = 0f
    private var scrubFired = false

    /**
     * Where the fingers are now. A held gesture emits no events, so [onIdleTick] has no event to
     * read a position from and needs the last one seen.
     */
    var centroid: Offset = Offset.Zero
        private set

    /** True once every finger has lifted — the gesture is over and this instance is spent. */
    var isFinished: Boolean = false
        private set

    fun start(id: PointerId, position: Offset, uptimeMs: Long) {
        startMs = uptimeMs
        origins.clear()
        origins[id] = position
        maxPointers = 1
        pressedNow = 1
        moved = false
        repeats = 0
        centroid = position
        scrubDirection = 0
        scrubReversals = 0
        scrubAnchorX = position.x
        scrubFired = false
        isFinished = false
    }

    /**
     * No event arrived within [REPEAT_TICK_MS] — fingers held perfectly still emit nothing, and this
     * is what drives Procreate's hold-to-repeat (keep two fingers down to step rapidly back through
     * history) and the four-finger hold that opens the QuickMenu.
     */
    fun onIdleTick(nowMs: Long, suppressed: Boolean): GestureSlot? {
        if (suppressed || moved || isFinished) return null
        if (pressedNow != maxPointers) return null
        if (nowMs - startMs < REPEAT_START_MS) return null
        return when (maxPointers) {
            2 -> { repeats++; GestureSlot.TWO_FINGER_TAP }
            3 -> { repeats++; GestureSlot.THREE_FINGER_TAP }
            // Fires once, not on every tick like the history repeats — and bumping `repeats` is also
            // what stops the eventual lift from reading as a four-finger tap and toggling the UI on
            // top of opening the menu.
            4 -> if (repeats == 0) { repeats++; GestureSlot.FOUR_FINGER_HOLD } else null
            else -> null
        }
    }

    /**
     * A pointer event: [pressed] is every pointer still down (empty on the final lift), [uptimeMs]
     * the event's own timestamp.
     */
    fun onEvent(pressed: List<TouchPoint>, uptimeMs: Long, suppressed: Boolean): GestureSlot? {
        if (isFinished) return null

        if (pressed.isNotEmpty()) {
            centroid = pressed.fold(Offset.Zero) { acc, p -> acc + p.position } / pressed.size.toFloat()
        }

        if (pressed.size > maxPointers) {
            // A finger joined: the gesture is only now assembled, so stillness starts here.
            val wasPanning = maxPointers >= 2 && moved
            maxPointers = pressed.size
            moved = wasPanning
            origins.clear()
            pressed.forEach { origins[it.id] = it.position }
            scrubDirection = 0
            scrubReversals = 0
            scrubAnchorX = centroid.x
        }
        pressedNow = pressed.size

        for (point in pressed) {
            val origin = origins.getOrPut(point.id) { point.position }
            if ((point.position - origin).getDistance() > slopPx) moved = true
        }

        // Three-finger scrub: count direction reversals of the centroid's horizontal travel. Each
        // leg must clear SCRUB_LEG_PX so a tremor during a three-finger hold can't accumulate into a
        // false clear.
        if (!scrubFired && maxPointers == 3 && pressedNow == 3) {
            val dx = centroid.x - scrubAnchorX
            if (abs(dx) >= SCRUB_LEG_PX) {
                val dir = if (dx > 0f) 1 else -1
                if (scrubDirection != 0 && dir != scrubDirection) scrubReversals++
                scrubDirection = dir
                scrubAnchorX = centroid.x
                if (scrubReversals >= SCRUB_MIN_REVERSALS && !suppressed) {
                    scrubFired = true
                    return GestureSlot.THREE_FINGER_SCRUB
                }
            }
        }

        if (pressed.isEmpty()) {
            isFinished = true
            val duration = uptimeMs - startMs
            if (repeats > 0 || scrubFired || moved || duration > TAP_MAX_DURATION_MS || suppressed) {
                return null
            }
            return when (maxPointers) {
                2 -> GestureSlot.TWO_FINGER_TAP
                3 -> GestureSlot.THREE_FINGER_TAP
                4 -> GestureSlot.FOUR_FINGER_TAP
                else -> null
            }
        }
        return null
    }
}

/**
 * Procreate's signature history gestures: **two-finger tap = undo, three-finger tap = redo**,
 * **hold two/three fingers to repeat** (rapid step-back through history), **four-finger tap** to
 * toggle the full-screen (UI-hidden) view, **four-finger hold** for the QuickMenu, and a
 * **three-finger scrub** to clear the layer. Every slot is user-reassignable, so the observer
 * reports a [GestureSlot] and the host decides what it does.
 *
 * A pure observer on [PointerEventPass.Initial]: it never consumes anything, so it coexists with
 * every other handler (drawing, pan/zoom, taps) — a qualifying tap is short, still, and lands
 * 2–4 fingers; anything travelling is someone else's gesture.
 *
 * Install it as high in the tree as possible. These gestures are meant to work anywhere on screen,
 * and the nav rail, the floating panels and the onscreen chrome are siblings of the canvas rather
 * than children of it — an observer mounted on the canvas simply never sees a tap that lands on any
 * of them.
 *
 * [onGesture] is read through [rememberUpdatedState], so the pointer loop — which is keyed on [gate]
 * alone and therefore never restarts — always calls the *current* callback. Capturing it once meant
 * a gesture remapped in Settings kept firing the mapping that was live at first composition until
 * the process restarted.
 */
@Composable
fun Modifier.multiFingerTaps(
    gate: StrokeGate,
    onGesture: (slot: GestureSlot, at: Offset) -> Unit,
): Modifier {
    val current = rememberUpdatedState(onGesture)
    return this.pointerInput(gate) {
        val recognizer = MultiFingerRecognizer(viewConfiguration.touchSlop * 1.5f)
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // A fresh first-down means no finger was down an instant ago, so any stroke latch still
            // standing is stale. Clearing it here is what makes these gestures unconditional.
            gate.onGestureStart()
            val cancelsAtStart = gate.cancelCount
            // Suppressed only by a stroke that is live right now, or by one this very gesture threw
            // away — never by a wall-clock window that outlives the gesture that opened it.
            fun suppressed(): Boolean = gate.strokeActive || gate.cancelCount != cancelsAtStart

            recognizer.start(first.id, first.position, first.uptimeMillis)

            while (true) {
                // Timed wait: fingers held perfectly still emit no events, and the timeout is what
                // drives the hold-to-repeat branch. withTimeoutOrNull here is
                // AwaitPointerEventScope's own member.
                val event = withTimeoutOrNull(REPEAT_TICK_MS) {
                    awaitPointerEvent(PointerEventPass.Initial)
                }

                val fired = if (event == null) {
                    recognizer.onIdleTick(SystemClock.uptimeMillis(), suppressed())
                } else {
                    val pressed = event.changes
                        .filter { it.pressed }
                        .map { TouchPoint(it.id, it.position) }
                    recognizer.onEvent(
                        pressed = pressed,
                        uptimeMs = event.changes.maxOf { it.uptimeMillis },
                        suppressed = suppressed(),
                    )
                }

                if (fired != null) current.value(fired, recognizer.centroid)
                if (recognizer.isFinished) break
            }
        }
    }
}

/**
 * Looks up what [slot] is currently assigned to in [mapping] (falling back to the slot's own
 * historical default if the map is somehow missing an entry) and fires it. [at] is only meaningful
 * for [GestureAction.QUICK_MENU] (opens where the gesture landed); every other action ignores it.
 */
fun performGestureAction(
    mapping: Map<GestureSlot, GestureAction>,
    slot: GestureSlot,
    vm: EditorViewModel,
    at: Offset = Offset.Zero,
) {
    when (mapping[slot] ?: slot.defaultAction) {
        GestureAction.NONE -> {}
        GestureAction.UNDO -> vm.onUndoClicked()
        GestureAction.REDO -> vm.onRedoClicked()
        GestureAction.HIDE_UI -> vm.toggleHideUi()
        GestureAction.QUICK_MENU -> vm.onOpenQuickMenu(at)
        GestureAction.CLEAR_LAYER -> vm.onClearLayer()
    }
}
