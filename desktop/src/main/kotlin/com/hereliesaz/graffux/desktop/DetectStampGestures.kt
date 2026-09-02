package com.hereliesaz.graffux.desktop

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp

/**
 * A hand-rolled press/drag/release gesture loop, deliberately NOT [androidx.compose.foundation.gestures.detectDragGestures]:
 * that helper's `onDrag`/`onDragEnd` callbacks are plain (non-suspend) lambdas, which forced an
 * earlier version of this file to fire-and-forget a `scope.launch` per move event with no ordering
 * guarantee against the next callback — `onDragEnd` could (and reliably did) run before the render
 * launched by the last `onDrag` had even started, silently dropping the tail of every stroke. Here,
 * [onStart]/[onMove]/[onEnd] are suspend callbacks invoked directly from this function's own
 * coroutine, so each one fully completes (its render is awaited) before the next pointer event is
 * even read — no separate coroutine, no ordering race.
 *
 * Each `awaitPointerEventScope { ... }` block below is kept tiny (only reading the next event) and
 * closed before calling [onStart]/[onMove]/[onEnd]: `AwaitPointerEventScope` is a
 * `@RestrictsSuspension` scope, so those callbacks — arbitrary suspend lambdas, not members of that
 * scope — cannot be invoked from inside it; they run in this function's own, unrestricted, suspend
 * context instead, between the small scoped blocks that fetch each event.
 *
 * Forwards each change's own `PointerInputChange.pressure` — Compose Multiplatform reads this from
 * the platform pointer event, which on Windows includes real Windows-Ink pressure for a Surface Pen
 * (`PointerType.Stylus`); elsewhere (mouse, a non-pressure touchpad) it is always `1f`, so callers
 * degrade gracefully to a constant-pressure stroke rather than failing. This intentionally does not
 * read tilt: Compose Multiplatform Desktop does not currently expose stylus tilt, only pressure and
 * [androidx.compose.ui.input.pointer.PointerType] — see DESKTOP.md.
 */
suspend fun PointerInputScope.detectStampGestures(
    onStart: suspend (position: Offset, pressure: Float) -> Unit,
    onMove: suspend (position: Offset, pressure: Float) -> Unit,
    onEnd: suspend () -> Unit,
) {
    while (true) {
        val down = awaitPointerEventScope {
            awaitFirstDown(requireUnconsumed = false).also { it.consume() }
        }
        onStart(down.position, down.pressure)
        var pointerId = down.id
        while (true) {
            val change = awaitPointerEventScope {
                val event = awaitPointerEvent()
                event.changes.firstOrNull { it.id == pointerId }?.also { it.consume() }
            } ?: break
            if (change.changedToUp()) {
                onEnd()
                break
            }
            onMove(change.position, change.pressure)
            pointerId = change.id
        }
    }
}
